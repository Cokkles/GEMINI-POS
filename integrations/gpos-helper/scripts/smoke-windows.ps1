[CmdletBinding()]
param(
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\dist\win-x64'),
    [int]$Port = 47931
)

$ErrorActionPreference = 'Stop'
$directory = (Resolve-Path $ArtifactDirectory).Path
$executable = Join-Path $directory 'gpos-helper.exe'
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw "Packaged executable not found: $executable" }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be between 1024 and 65535.' }

$stdout = Join-Path $directory 'smoke.stdout.log'
$stderr = Join-Path $directory 'smoke.stderr.log'
$arguments = @("--Helper:Port=$Port", '--Helper:ListenAddress=127.0.0.1', '--Helper:DevelopmentMode=true', '--Helper:LaunchBrowser=false', '--Helper:HeartbeatSeconds=5')
$process = Start-Process -FilePath $executable -ArgumentList $arguments -WorkingDirectory $directory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
try {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    $health = $null
    while ([DateTimeOffset]::UtcNow -lt $deadline -and -not $process.HasExited) {
        try { $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/health" -TimeoutSec 2; break } catch { Start-Sleep -Milliseconds 200 }
    }
    if ($null -eq $health -or $health.status -ne 'AVAILABLE') { throw 'Packaged helper did not become available within 15 seconds.' }
    $capabilities = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/capabilities" -TimeoutSec 5
    if ('helper.health' -notin $capabilities.capabilities -or 'helper.auth' -notin $capabilities.capabilities) { throw 'Packaged helper did not advertise required capabilities.' }
    $root = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -TimeoutSec 5 -UseBasicParsing
    if ($root.StatusCode -ne 200 -or $root.Content -notmatch 'GPOS HELPER CONTROL') { throw 'Packaged control surface was not served.' }
    Write-Host 'PASS  packaged executable health, capabilities and control surface'
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force; $process.WaitForExit() }
}
