# Shared helpers, dot-sourced by the other scripts.
$ErrorActionPreference = 'Stop'
$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:EnvFile  = Join-Path $RepoRoot '.env'
$script:NetworkName = 'faultlab-net'

function Assert-Docker {
    docker version --format '{{.Server.Version}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker is not running or not reachable.' }
    docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required.' }
}

function Ensure-Network {
    $existing = docker network ls --filter "name=^$NetworkName$" --format '{{.Name}}'
    if (-not $existing) {
        Write-Host "Creating Docker network $NetworkName" -ForegroundColor Cyan
        docker network create $NetworkName | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to create network $NetworkName" }
    }
}

function Get-EnvFileArgs {
    if (Test-Path $EnvFile) { return @('--env-file', $EnvFile) }
    Write-Warning ".env not found - using defaults. Copy .env.example to .env to customise."
    return @()
}

function Invoke-Compose {
    param([string]$ProjectDir, [string[]]$Arguments)
    $envArgs = Get-EnvFileArgs
    Push-Location (Join-Path $RepoRoot $ProjectDir)
    try {
        & docker compose @envArgs @Arguments
        if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed in $ProjectDir" }
    } finally { Pop-Location }
}

function Wait-Http {
    param([string]$Url, [int]$TimeoutSeconds = 180, [string]$Name = $Url)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 300) {
                Write-Host "  OK  $Name" -ForegroundColor Green
                return $true
            }
        } catch { Start-Sleep -Seconds 3 }
    }
    Write-Host "  FAIL $Name ($Url) not ready after $TimeoutSeconds s" -ForegroundColor Red
    return $false
}

function Test-UseLocalDb {
    if (-not (Test-Path $EnvFile)) { return $true }
    $line = Get-Content $EnvFile | Where-Object { $_ -match '^\s*USE_LOCAL_DB\s*=' } | Select-Object -Last 1
    if (-not $line) { return $true }
    return ($line -split '=', 2)[1].Trim().ToLower() -eq 'true'
}
