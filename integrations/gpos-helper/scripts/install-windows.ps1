[CmdletBinding()]
param(
    [string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\dist\win-x64'),
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\GPOS Helper'),
    [string]$ConfigurationDirectory = (Join-Path $env:LOCALAPPDATA 'GPOS\gpos-helper'),
    [string]$AppsScriptEndpoint,
    [string]$GoogleOAuthClientId,
    [string]$GoogleOAuthRedirectUri,
    [string]$AllowedEmail,
    [SecureString]$OAuthClientSecret,
    [int]$Port = 47831,
    [switch]$NoShortcuts,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be between 1024 and 65535.' }

function Read-Required([string]$Prompt, [string]$Current) {
    $suffix = if ([string]::IsNullOrWhiteSpace($Current)) { '' } else { ' [press Enter to keep current value]' }
    $value = Read-Host ($Prompt + $suffix)
    if ([string]::IsNullOrWhiteSpace($value)) { $value = $Current }
    if ([string]::IsNullOrWhiteSpace($value)) { throw "$Prompt is required." }
    return $value.Trim()
}

$ArtifactDirectory = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
& (Join-Path $PSScriptRoot 'verify-package.ps1') -ArtifactDirectory $ArtifactDirectory

$appsScriptEndpoint = Read-Required 'Apps Script HTTPS endpoint' $(if ($AppsScriptEndpoint) { $AppsScriptEndpoint } else { $env:GPOS_Helper__AppsScriptEndpoint })
$clientId = Read-Required 'Google OAuth web client ID' $(if ($GoogleOAuthClientId) { $GoogleOAuthClientId } else { $env:GPOS_Helper__GoogleOAuth__ClientId })
$redirectUri = Read-Required 'Google OAuth redirect URI' $(if ($GoogleOAuthRedirectUri) { $GoogleOAuthRedirectUri } elseif ($env:GPOS_Helper__GoogleOAuth__RedirectUri) { $env:GPOS_Helper__GoogleOAuth__RedirectUri } else { "http://127.0.0.1:$Port/api/v1/auth/callback" })
$allowedEmail = Read-Required 'Allowed Google email address' $(if ($AllowedEmail) { $AllowedEmail } else { $env:GPOS_Helper__AllowedEmails__0 })

try { $appsScriptUri = [Uri]$appsScriptEndpoint } catch { throw 'Apps Script endpoint must be an absolute HTTPS URL.' }
if (-not $appsScriptUri.IsAbsoluteUri -or $appsScriptUri.Scheme -ne 'https') { throw 'Apps Script endpoint must use HTTPS.' }
if ($redirectUri -ne "http://127.0.0.1:$Port/api/v1/auth/callback") { throw 'OAuth redirect URI must exactly match the helper loopback callback.' }

if ($null -eq $OAuthClientSecret) {
    Write-Host 'Paste the rotated OAuth client secret when prompted. Input is hidden and is never written as plaintext.'
    $secureSecret = Read-Host 'Google OAuth client secret' -AsSecureString
} else { $secureSecret = $OAuthClientSecret }
if ($secureSecret.Length -eq 0) { throw 'Google OAuth client secret is required.' }
try { $protectedSecret = $secureSecret | ConvertFrom-SecureString }
catch { throw 'Windows could not protect the OAuth client secret for this user profile. Run the installer from your normal signed-in PowerShell session.' }

$configDirectory = $ConfigurationDirectory
$appDirectory = Join-Path $InstallDirectory 'app'
if (Test-Path -LiteralPath $appDirectory) { throw "An installation already exists at $InstallDirectory. Stop GPOS Helper and remove that installation before reinstalling." }

New-Item -ItemType Directory -Path $appDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
Copy-Item -Path (Join-Path $ArtifactDirectory '*') -Destination $appDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'launch-installed.ps1') -Destination $InstallDirectory -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'stop-installed.ps1') -Destination $InstallDirectory -Force
& (Join-Path $PSScriptRoot 'verify-package.ps1') -ArtifactDirectory $appDirectory

$settings = [ordered]@{
    apps_script_endpoint = $appsScriptEndpoint
    google_oauth_client_id = $clientId
    google_oauth_redirect_uri = $redirectUri
    allowed_email = $allowedEmail
    port = $Port
}
$settings | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $configDirectory 'production.json') -Encoding utf8
$protectedSecret | Set-Content -LiteralPath (Join-Path $configDirectory 'oauth-client-secret.dpapi') -Encoding ascii
$protectedSecret = $null

$launchScript = Join-Path $InstallDirectory 'launch-installed.ps1'
$stopScript = Join-Path $InstallDirectory 'stop-installed.ps1'
if (-not $NoShortcuts) {
    $powerShellExecutable = (Get-Process -Id $PID).Path
    $desktop = [Environment]::GetFolderPath('Desktop')
    $startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) 'GPOS Helper'
    New-Item -ItemType Directory -Path $startMenu -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell

    function New-HelperShortcut([string]$Path, [string]$Script, [string]$Description) {
        $shortcut = $shell.CreateShortcut($Path)
        $shortcut.TargetPath = $powerShellExecutable
        $shortcut.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Script`""
        $shortcut.WorkingDirectory = $InstallDirectory
        $shortcut.Description = $Description
        $shortcut.IconLocation = "$(Join-Path $appDirectory 'gpos-helper.exe'),0"
        $shortcut.Save()
    }

    New-HelperShortcut (Join-Path $desktop 'GPOS Helper.lnk') $launchScript 'Open the GPOS Helper dashboard'
    New-HelperShortcut (Join-Path $startMenu 'GPOS Helper.lnk') $launchScript 'Open the GPOS Helper dashboard'
    New-HelperShortcut (Join-Path $startMenu 'Stop GPOS Helper.lnk') $stopScript 'Stop the local GPOS Helper service'
}

Write-Host "PASS  GPOS Helper installed at $InstallDirectory" -ForegroundColor Green
Write-Host 'The OAuth client secret is protected for the current Windows user with DPAPI.'
Write-Host 'Use the GPOS Helper Desktop or Start Menu shortcut from now on.'

if (-not $NoStart) { & $launchScript }

