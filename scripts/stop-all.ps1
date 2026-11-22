<#
.SYNOPSIS  Stops both stacks. -RemoveVolumes also deletes the local database and observability data.
#>
param([switch]$RemoveVolumes)
. "$PSScriptRoot/_common.ps1"
Assert-Docker
$composeArgs = @('--profile', 'local-db', 'down')
if ($RemoveVolumes) { $composeArgs += '-v' }
Invoke-Compose -ProjectDir 'applications' -Arguments $composeArgs
& "$PSScriptRoot/stop-observability.ps1" -RemoveVolumes:$RemoveVolumes
