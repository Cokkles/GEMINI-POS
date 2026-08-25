[CmdletBinding()]
param(
    [int]$Port = 47831,
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\dist\win-x64'),
    [switch]$Source,
    [switch]$Preflight
)

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

$launchTarget = $null
if ($Source) {
    $project = Join-Path $PSScriptRoot '..\src\Gpos.Helper\Gpos.Helper.csproj'
    if (-not (Test-Path -LiteralPath $project -PathType Leaf)) { throw 'Helper source project was not found.' }
    $launchTarget = 'source project'
} else {
    & (Join-Path $PSScriptRoot 'verify-package.ps1') -ArtifactDirectory $ArtifactDirectory
    $ArtifactDirectory = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
    $executable = Join-Path $ArtifactDirectory 'gpos-helper.exe'
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Verified package does not contain gpos-helper.exe.' }
    $launchTarget = 'verified package'
}

if ($Preflight) {
    Write-Host "PASS  production preflight completed for $launchTarget"
    Write-Host 'No helper process was started and sensitive values were not displayed.'
    return
}

Write-Host "Starting gpos-helper production mode on http://127.0.0.1:$Port"
Write-Host "Launch target: $launchTarget"
Write-Host 'Sensitive configuration values will not be displayed.'
if ($Source) { dotnet run --project $project --no-launch-profile }
else { & $executable }
exit $LASTEXITCODE

