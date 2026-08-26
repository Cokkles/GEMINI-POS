[CmdletBinding()]
param([int]$HealthWaitSeconds = 20)

$ErrorActionPreference = 'Stop'
$appDirectory = Join-Path $PSScriptRoot 'app'
$executable = Join-Path $appDirectory 'gpos-helper.exe'
$configDirectory = Join-Path $env:LOCALAPPDATA 'GPOS\gpos-helper'
$configPath = Join-Path $configDirectory 'production.json'
$secretPath = Join-Path $configDirectory 'oauth-client-secret.dpapi'
$logDirectory = Join-Path $configDirectory 'logs'

if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Installed gpos-helper.exe was not found. Run install-windows.ps1 again.' }
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'Installed production configuration was not found. Run install-windows.ps1 again.' }
if (-not (Test-Path -LiteralPath $secretPath -PathType Leaf)) { throw 'Protected OAuth client secret was not found. Run install-windows.ps1 again.' }

$settings = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$dashboardUrl = "http://127.0.0.1:$($settings.port)/"
$healthUrl = "${dashboardUrl}api/v1/health"

try {
    $running = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
    if ($running.status -eq 'AVAILABLE') { Start-Process $dashboardUrl; return }
} catch { }

$protectedSecret = (Get-Content -LiteralPath $secretPath -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($protectedSecret)) { throw 'Protected OAuth client secret was empty. Run install-windows.ps1 again.' }
$secureSecret = ConvertTo-SecureString $protectedSecret
$plainSecret = [System.Net.NetworkCredential]::new('', $secureSecret).Password
try {
    $env:GPOS_Helper__AppsScriptEndpoint = [string]$settings.apps_script_endpoint
    $env:GPOS_Helper__GoogleOAuth__ClientId = [string]$settings.google_oauth_client_id
    $env:GPOS_Helper__GoogleOAuth__ClientSecret = $plainSecret
    $env:GPOS_Helper__GoogleOAuth__RedirectUri = [string]$settings.google_oauth_redirect_uri
    $env:GPOS_Helper__AllowedEmails__0 = [string]$settings.allowed_email
    $env:GPOS_Helper__AllowedOrigins__0 = 'https://cokkles.github.io'
    $env:GPOS_Helper__ListenAddress = '127.0.0.1'
    $env:GPOS_Helper__Port = [string]$settings.port
    $env:GPOS_Helper__DevelopmentMode = 'false'
    $env:GPOS_Helper__LaunchBrowser = 'false'

    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    $stdout = Join-Path $logDirectory 'gpos-helper.stdout.log'
    $stderr = Join-Path $logDirectory 'gpos-helper.stderr.log'
    $process = Start-Process -FilePath $executable -WorkingDirectory $appDirectory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
} finally {
    $plainSecret = $null
    Remove-Item Env:GPOS_Helper__GoogleOAuth__ClientSecret -ErrorAction SilentlyContinue
}

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($HealthWaitSeconds)
$health = $null
while ([DateTimeOffset]::UtcNow -lt $deadline -and -not $process.HasExited) {
    try {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 2
        if ($health.status -eq 'AVAILABLE') { break }
    } catch { Start-Sleep -Milliseconds 250 }
}

if ($null -eq $health -or $health.status -ne 'AVAILABLE') {
    if ($process.HasExited) { throw "gpos-helper exited during startup. Review $stderr" }
    Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
    throw "gpos-helper did not become available within $HealthWaitSeconds seconds. Review $stderr"
}

Start-Process $dashboardUrl

