<#
.SYNOPSIS  Docker-based end-to-end smoke test. Requires both stacks to be running.
           Verifies endpoints, idempotency, failure modes + retries, Prometheus metrics, Tempo traces
           and (if running) Elasticsearch logs. Exits non-zero on any failure.
           Requires PowerShell 7+.
#>
param([switch]$SkipLogs)
. "$PSScriptRoot/_common.ps1"
$failures = 0
function Check($name, [scriptblock]$test) {
    try {
        $ok = & $test
        if ($ok) { Write-Host "  PASS $name" -ForegroundColor Green }
        else { Write-Host "  FAIL $name" -ForegroundColor Red; $script:failures++ }
    } catch { Write-Host "  FAIL $name - $($_.Exception.Message)" -ForegroundColor Red; $script:failures++ }
}
function Post($url, $body, $headers = @{}) {
    Invoke-WebRequest -Method Post -Uri $url -Body ($body | ConvertTo-Json) -ContentType 'application/json' `
        -Headers $headers -UseBasicParsing -SkipHttpErrorCheck
}
$order = 'http://localhost:8080'; $inv = 'http://localhost:8081'

Write-Host 'Health' -ForegroundColor Cyan
Check 'order-service healthy'     { (Invoke-RestMethod "$order/actuator/health").status -eq 'UP' }
Check 'inventory-service healthy' { (Invoke-RestMethod "$inv/actuator/health").status -eq 'UP' }
Invoke-RestMethod -Method Post "$inv/admin/reset" | Out-Null

Write-Host 'Happy path + idempotency' -ForegroundColor Cyan
$key = [guid]::NewGuid().ToString()
$before = (Invoke-RestMethod "$inv/api/inventory/P-0001").available
$r1 = Post "$order/api/orders" @{ productId = 'P-0001'; quantity = 2 } @{ 'Idempotency-Key' = $key }
$r2 = Post "$order/api/orders" @{ productId = 'P-0001'; quantity = 2 } @{ 'Idempotency-Key' = $key }
$o1 = $r1.Content | ConvertFrom-Json
Check 'POST /api/orders -> 201 CONFIRMED' { $r1.StatusCode -eq 201 -and $o1.status -eq 'CONFIRMED' }
Check 'replay same key -> 200 same order' { $r2.StatusCode -eq 200 -and ($r2.Content | ConvertFrom-Json).orderId -eq $o1.orderId }
Check 'stock decremented exactly once'   { (Invoke-RestMethod "$inv/api/inventory/P-0001").available -eq ($before - 2) }
Check 'GET /api/orders/{id} -> 200'      { (Invoke-RestMethod "$order/api/orders/$($o1.orderId)").status -eq 'CONFIRMED' }
Check 'GET reservation in inventory'     { (Invoke-RestMethod "$inv/api/inventory/reservations/$($o1.orderId)").quantity -eq 2 }
Check 'same key, other payload -> 422'   { (Post "$order/api/orders" @{ productId = 'P-0002'; quantity = 1 } @{ 'Idempotency-Key' = $key }).StatusCode -eq 422 }
Check 'invalid request -> 400'           { (Post "$order/api/orders" @{ productId = ''; quantity = 0 }).StatusCode -eq 400 }
Check 'insufficient stock -> 409'        { (Post "$order/api/orders" @{ productId = 'P-LIMITED'; quantity = 999 }).StatusCode -eq 409 }

Write-Host 'Failure modes' -ForegroundColor Cyan
Post "$inv/admin/failure-mode" @{ mode = 'FAIL_FIRST_2' } | Out-Null
$r = Post "$order/api/orders" @{ productId = 'P-0003'; quantity = 1 } @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
Check 'FAIL_FIRST_2 -> 201 after 3 attempts (retries)' { $r.StatusCode -eq 201 -and $r.Headers['X-Downstream-Attempts'] -eq '3' }

Post "$inv/admin/failure-mode" @{ mode = 'PROCESS_THEN_FAIL_ONCE' } | Out-Null
$before = (Invoke-RestMethod "$inv/api/inventory/P-0004").available
$k2 = [guid]::NewGuid().ToString()
# 500 is not in RETRY_STATUS_CODES (502,503,504): the order is accepted with an UNKNOWN outcome...
$r = Post "$order/api/orders" @{ productId = 'P-0004'; quantity = 1 } @{ 'Idempotency-Key' = $k2 }
Check 'PROCESS_THEN_FAIL_ONCE: lost response -> 202 RESERVATION_UNKNOWN' {
    $r.StatusCode -eq 202 -and ($r.Content | ConvertFrom-Json).status -eq 'RESERVATION_UNKNOWN' }
# ...and the client retrying with the same key resolves it via the idempotent inventory replay.
$r = Post "$order/api/orders" @{ productId = 'P-0004'; quantity = 1 } @{ 'Idempotency-Key' = $k2 }
Check 'client retry with same key -> 200 CONFIRMED' { $r.StatusCode -eq 200 -and ($r.Content | ConvertFrom-Json).status -eq 'CONFIRMED' }
Check 'stock reserved exactly once' { (Invoke-RestMethod "$inv/api/inventory/P-0004").available -eq ($before - 1) }

Post "$inv/admin/failure-mode" @{ mode = 'ALWAYS_FAIL' } | Out-Null
$statuses = 1..15 | ForEach-Object {
    (Post "$order/api/orders" @{ productId = 'P-0005'; quantity = 1 } @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }).StatusCode }
Check 'ALWAYS_FAIL: degrades to 202/503 (no 500s)' { -not ($statuses | Where-Object { $_ -eq 500 }) }
Check 'ALWAYS_FAIL: circuit breaker opened (503 FAILED seen)' { $statuses -contains 503 }
Invoke-RestMethod -Method Post "$inv/admin/reset" | Out-Null

Write-Host 'Observability' -ForegroundColor Cyan
Check 'Prometheus scrapes order-service' {
    (Invoke-RestMethod 'http://localhost:9090/api/v1/query?query=up{job="order-service"}').data.result[0].value[1] -eq '1' }
Check 'Prometheus has retry metrics' {
    (Invoke-RestMethod 'http://localhost:9090/api/v1/query?query=faultlab_downstream_attempts_total').data.result.Count -gt 0 }
Check 'Prometheus has Hikari metrics' {
    (Invoke-RestMethod 'http://localhost:9090/api/v1/query?query=hikaricp_connections_max').data.result.Count -ge 2 }
Check 'Grafana dashboards provisioned' {
    (Invoke-RestMethod 'http://localhost:3000/api/search?tag=faultlab').Count -ge 2 }
Start-Sleep -Seconds 10   # let the collector flush spans
Check 'Tempo: trace spans both services' {
    $search = Invoke-RestMethod 'http://localhost:3200/api/search?tags=service.name%3Dorder-service&limit=20'
    $found = $false
    foreach ($t in $search.traces) {
        $trace = Invoke-RestMethod "http://localhost:3200/api/traces/$($t.traceID)"
        $services = $trace.batches | ForEach-Object { ($_.resource.attributes | Where-Object key -eq 'service.name').value.stringValue }
        if (($services -contains 'order-service') -and ($services -contains 'inventory-service')) { $found = $true; break }
    }
    $found }

if (-not $SkipLogs) {
    $esUp = $false
    try { $esUp = (Invoke-WebRequest 'http://localhost:9200' -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { }
    if ($esUp) {
        Start-Sleep -Seconds 5
        Check 'Elasticsearch received structured logs with trace_id' {
            $q = Invoke-RestMethod 'http://localhost:9200/faultlab-logs-*/_search?q=_exists_:trace_id&size=1'
            $q.hits.total.value -gt 0 }
    } else { Write-Host '  SKIP Elasticsearch not running (logging profile disabled)' -ForegroundColor Yellow }
}

if ($failures -gt 0) { Write-Host "`n$failures check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "`nAll smoke checks passed" -ForegroundColor Green
