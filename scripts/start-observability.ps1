<#
.SYNOPSIS  Starts Prometheus, Grafana, Tempo and the OpenTelemetry Collector (+ Elasticsearch/Logstash with -Logging).
.EXAMPLE   ./scripts/start-observability.ps1 -Logging
#>
param([switch]$Logging)
. "$PSScriptRoot/_common.ps1"
Assert-Docker
Ensure-Network

$composeArgs = @()
if ($Logging) { $composeArgs += @('--profile', 'logging') }
$composeArgs += @('up', '-d', '--wait')
Invoke-Compose -ProjectDir 'observability' -Arguments $composeArgs

Wait-Http 'http://localhost:9090/-/ready' -Name 'Prometheus' | Out-Null
Wait-Http 'http://localhost:3000/api/health' -Name 'Grafana' | Out-Null
Wait-Http 'http://localhost:3200/ready' -Name 'Tempo' -TimeoutSeconds 120 | Out-Null
Wait-Http 'http://localhost:13133/' -Name 'OTel Collector' | Out-Null

if ($Logging) {
    if (Wait-Http 'http://localhost:9200/_cluster/health' -Name 'Elasticsearch' -TimeoutSeconds 240) {
        $template = Get-Content (Join-Path $RepoRoot 'observability/elasticsearch/index-template.json') -Raw
        Invoke-RestMethod -Method Put -Uri 'http://localhost:9200/_index_template/faultlab-logs' `
            -ContentType 'application/json' -Body $template | Out-Null
        Write-Host '  OK  Elasticsearch index template installed' -ForegroundColor Green
    }
    Wait-Http 'http://localhost:9600/' -Name 'Logstash' -TimeoutSeconds 240 | Out-Null
    Write-Host 'Set LOGSTASH_ENABLED=true in .env and restart the apps to ship logs.' -ForegroundColor Yellow
}
Write-Host "`nGrafana: http://localhost:3000 (admin/admin)   Prometheus: http://localhost:9090" -ForegroundColor Cyan
