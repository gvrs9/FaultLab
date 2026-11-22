<#
.SYNOPSIS  Runs a k6 script in a container attached to faultlab-net.
.EXAMPLE   ./scripts/run-load-test.ps1 -Test smoke
.EXAMPLE   ./scripts/run-load-test.ps1 -Test constant-load -Rate 100 -Duration 5m
.EXAMPLE   ./scripts/run-load-test.ps1 -Test stress -Extra @{ MAX_RATE = '200' }
#>
param(
    [ValidateSet('smoke', 'constant-load', 'stress', 'spike', 'soak')]
    [string]$Test = 'smoke',
    [ValidateSet(10, 25, 50, 100, 200, 500)]
    [int]$Rate = 50,
    [string]$Duration = '2m',
    [string]$BaseUrl = 'http://order-service:8080',
    [hashtable]$Extra = @{},
    [string]$K6Image = 'grafana/k6:1.0.0'
)
. "$PSScriptRoot/_common.ps1"
Assert-Docker
Ensure-Network

$loadTests = Join-Path $RepoRoot 'load-tests'
$results = Join-Path $loadTests 'results'
New-Item -ItemType Directory -Force -Path $results | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$summaryFile = "/scripts/results/$Test-$Rate-$stamp.json"

$envArgs = @('-e', "BASE_URL=$BaseUrl", '-e', "RATE=$Rate", '-e', "DURATION=$Duration")
foreach ($k in $Extra.Keys) { $envArgs += @('-e', "$k=$($Extra[$k])") }

Write-Host "Running $Test (rate=$Rate, duration=$Duration) against $BaseUrl" -ForegroundColor Cyan
docker run --rm --network $NetworkName -v "${loadTests}:/scripts" @envArgs $K6Image `
    run --summary-export $summaryFile "/scripts/$Test.js"
$code = $LASTEXITCODE
Write-Host "Summary: load-tests/results/$(Split-Path $summaryFile -Leaf)" -ForegroundColor Cyan
if ($code -ne 0) { Write-Warning "k6 exited with $code (thresholds crossed or errors)." }
exit $code
