[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ClientJsonPath,
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\GPOS Helper'),
    [string]$ConfigurationDirectory = (Join-Path $env:LOCALAPPDATA 'GPOS\gpos-helper')
)

$ErrorActionPreference = 'Stop'
$clientJsonPath = (Resolve-Path -LiteralPath $ClientJsonPath).Path
$configPath = Join-Path $ConfigurationDirectory 'production.json'
$secretPath = Join-Path $ConfigurationDirectory 'oauth-client-secret.dpapi'
$stopScript = Join-Path $InstallDirectory 'stop-installed.ps1'

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'Installed GPOS Helper configuration was not found. Run install-windows.ps1 first.'
}

$credential = Get-Content -LiteralPath $clientJsonPath -Raw | ConvertFrom-Json
if ($null -eq $credential.installed -or $null -ne $credential.web) {
    throw 'The selected JSON must contain a Google OAuth Desktop app credential under the installed property.'
}

$clientId = [string]$credential.installed.client_id
$clientSecret = [string]$credential.installed.client_secret
$redirects = @($credential.installed.redirect_uris)
if ([string]::IsNullOrWhiteSpace($clientId) -or -not $clientId.EndsWith('.apps.googleusercontent.com', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The Desktop OAuth client ID is missing or invalid.'
}
if ([string]::IsNullOrWhiteSpace($clientSecret)) {
    throw 'The Desktop OAuth client value is missing.'
}
if (-not ($redirects | Where-Object { $_ -match '^http://(localhost|127\.0\.0\.1)(:|/|$)' })) {
    throw 'The Desktop OAuth client does not declare a loopback redirect.'
}

if (Test-Path -LiteralPath $stopScript -PathType Leaf) {
    & $stopScript
}

$settings = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$settings.google_oauth_client_id = $clientId
$settings | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding utf8

$secureSecret = ConvertTo-SecureString $clientSecret -AsPlainText -Force
try {
    $protectedSecret = $secureSecret | ConvertFrom-SecureString
    $protectedSecret | Set-Content -LiteralPath $secretPath -Encoding ascii -NoNewline
}
finally {
    $clientSecret = $null
    $protectedSecret = $null
    $secureSecret.Dispose()
    $credential = $null
}

Write-Host 'PASS  Installed Google Desktop OAuth client updated and protected for the current Windows user.' -ForegroundColor Green
Write-Host 'The source JSON was read in place and was not copied. Store or delete it according to your credential-handling policy.'
