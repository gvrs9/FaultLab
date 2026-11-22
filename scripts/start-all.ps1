<#
.SYNOPSIS  Starts the observability stack, then the application stack.
.PARAMETER Logging   Also start Elasticsearch + Logstash.
.PARAMETER LocalDb   Force the local PostgreSQL container (default: USE_LOCAL_DB in .env, true if unset).
.PARAMETER NoBuild   Skip rebuilding the application images.
.EXAMPLE   ./scripts/start-all.ps1 -Logging
#>
param([switch]$Logging, [switch]$LocalDb, [switch]$NoBuild)
. "$PSScriptRoot/_common.ps1"
Assert-Docker
Ensure-Network

& "$PSScriptRoot/start-observability.ps1" -Logging:$Logging

$useLocalDb = $LocalDb -or (Test-UseLocalDb)
$composeArgs = @()
if ($useLocalDb) { $composeArgs += @('--profile', 'local-db') }
$composeArgs += @('up', '-d', '--wait')
if (-not $NoBuild) { $composeArgs += '--build' }
Write-Host "`nStarting application stack (local DB: $useLocalDb)" -ForegroundColor Cyan
Invoke-Compose -ProjectDir 'applications' -Arguments $composeArgs

Wait-Http 'http://localhost:8081/actuator/health/readiness' -Name 'inventory-service' | Out-Null
Wait-Http 'http://localhost:8080/actuator/health/readiness' -Name 'order-service' | Out-Null
Write-Host "`norder-service:     http://localhost:8080/api/orders" -ForegroundColor Cyan
Write-Host   "inventory-service: http://localhost:8081/api/inventory/P-0001" -ForegroundColor Cyan
Write-Host   "Run ./scripts/smoke-test.ps1 to verify the full path." -ForegroundColor Cyan
