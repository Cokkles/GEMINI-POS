[CmdletBinding()]
param([int]$Port = 47831)

$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be between 1024 and 65535.' }

$required = @(
    'GPOS_Helper__AppsScriptEndpoint',
    'GPOS_Helper__GoogleOAuth__ClientId',
    'GPOS_Helper__GoogleOAuth__ClientSecret',
    'GPOS_Helper__GoogleOAuth__RedirectUri',
    'GPOS_Helper__AllowedEmails__0'
)
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) })
if ($missing.Count -gt 0) { throw ('Required production environment variables are missing: ' + ($missing -join ', ')) }

$endpoint = [Uri][Environment]::GetEnvironmentVariable('GPOS_Helper__AppsScriptEndpoint')
$redirect = [Uri][Environment]::GetEnvironmentVariable('GPOS_Helper__GoogleOAuth__RedirectUri')
if ($endpoint.Scheme -ne 'https') { throw 'Apps Script endpoint must use HTTPS.' }
if ($redirect.Scheme -ne 'http' -or $redirect.Host -notin @('127.0.0.1', 'localhost', '::1')) { throw 'OAuth redirect must use HTTP loopback.' }
if ($redirect.Port -ne $Port) { throw 'OAuth redirect port must match the helper port.' }

$env:GPOS_Helper__ListenAddress = '127.0.0.1'
$env:GPOS_Helper__Port = [string]$Port
$env:GPOS_Helper__DevelopmentMode = 'false'
$env:GPOS_Helper__LaunchBrowser = 'true'
$env:GPOS_Helper__AllowedOrigins__0 = 'https://cokkles.github.io'

$project = Join-Path $PSScriptRoot '..\src\Gpos.Helper\Gpos.Helper.csproj'
Write-Host "Starting gpos-helper production mode on http://127.0.0.1:$Port"
Write-Host 'Sensitive configuration values will not be displayed.'
dotnet run --project $project --no-launch-profile
exit $LASTEXITCODE
