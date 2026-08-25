[CmdletBinding()]
param(
    [ValidateSet('Development', 'Production')]
    [string]$Mode = 'Development',
    [int]$Port = 47831,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be between 1024 and 65535.' }

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dashboardUrl = "http://127.0.0.1:$Port/"
$healthUrl = "http://127.0.0.1:$Port/api/v1/health"

try {
    Invoke-RestMethod -Uri $healthUrl -TimeoutSec 1 | Out-Null
    throw "A helper is already running at $dashboardUrl. Stop it before starting another instance."
} catch {
    if ($_.Exception.Message -like 'A helper is already running*') { throw }
}

if ($Mode -eq 'Production') {
    $launchScript = Join-Path $PSScriptRoot 'run-production.ps1'
    & $launchScript -Port $Port -Preflight
} else {
    $launchScript = Join-Path $PSScriptRoot 'run-dev.ps1'
}

$powerShellExecutable = (Get-Process -Id $PID).Path
$escapedScript = $launchScript.Replace("'", "''")
$childCommand = "& '$escapedScript' -Port $Port"
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))
$helperProcess = Start-Process -FilePath $powerShellExecutable -ArgumentList @('-NoProfile', '-EncodedCommand', $encodedCommand) -WorkingDirectory $root -PassThru

Write-Host "Starting gpos-helper in $Mode mode..."
$deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
$health = $null
while ([DateTimeOffset]::UtcNow -lt $deadline -and -not $helperProcess.HasExited) {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        if ($health.status -eq 'AVAILABLE') { break }
    } catch {
        Start-Sleep -Milliseconds 250
    }
}

if ($null -eq $health -or $health.status -ne 'AVAILABLE') {
    if ($helperProcess.HasExited) { throw "The helper process exited before becoming available (exit code $($helperProcess.ExitCode)). Review its window for details." }
    throw 'The helper did not become available within 20 seconds.'
}

Write-Host "PASS  gpos-helper $($health.version) is available at $dashboardUrl" -ForegroundColor Green
if (-not $NoBrowser) {
    Start-Process $dashboardUrl
    Write-Host 'Dashboard opened in the default browser.'
}
Write-Host 'The helper is running in its own PowerShell window. Close that window or press Ctrl+C there to stop it.'

