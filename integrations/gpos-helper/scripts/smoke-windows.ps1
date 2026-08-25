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
    $live = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/live" -TimeoutSec 5
    $ready = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/ready" -TimeoutSec 5
    if ($live.status -ne 'ALIVE' -or $ready.status -ne 'READY') { throw 'Packaged helper probes did not report ALIVE and READY.' }
    $capabilities = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/capabilities" -TimeoutSec 5
    if ('helper.health' -notin $capabilities.capabilities -or 'helper.readiness' -notin $capabilities.capabilities -or 'helper.auth' -notin $capabilities.capabilities) { throw 'Packaged helper did not advertise required capabilities.' }
    $root = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -TimeoutSec 5 -UseBasicParsing
    if ($root.StatusCode -ne 200 -or $root.Content -notmatch 'GPOS HELPER CONTROL') { throw 'Packaged control surface was not served.' }
    if ([string]$root.Headers['Content-Security-Policy'] -notmatch "frame-ancestors 'none'" -or $root.Headers['X-Content-Type-Options'] -ne 'nosniff') { throw 'Packaged control surface security headers were incomplete.' }
    $auth = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/v1/auth/status" -TimeoutSec 5 -UseBasicParsing
    if ([string]$auth.Headers['Cache-Control'] -notmatch '(?i)no-store') { throw 'Packaged API response was cacheable.' }
    Write-Host 'PASS  packaged executable probes, capabilities, security policy and control surface'
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force; $process.WaitForExit() }
}

