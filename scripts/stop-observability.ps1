<#
.SYNOPSIS  Stops the observability stack. -RemoveVolumes also deletes stored metrics, traces and logs.
#>
param([switch]$RemoveVolumes)
. "$PSScriptRoot/_common.ps1"
Assert-Docker
$composeArgs = @('--profile', 'logging', 'down')
if ($RemoveVolumes) { $composeArgs += '-v' }
Invoke-Compose -ProjectDir 'observability' -Arguments $composeArgs
