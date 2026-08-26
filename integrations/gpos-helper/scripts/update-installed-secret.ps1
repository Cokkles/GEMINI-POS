[CmdletBinding()]
param([string]$ConfigurationDirectory = (Join-Path $env:LOCALAPPDATA 'GPOS\gpos-helper'))

$ErrorActionPreference = 'Stop'
$configPath = Join-Path $ConfigurationDirectory 'production.json'
$secretPath = Join-Path $ConfigurationDirectory 'oauth-client-secret.dpapi'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'Installed GPOS Helper configuration was not found. Run install-windows.ps1 first.' }

Write-Host 'Paste the current OAuth client secret for the configured Google web client. Input is hidden.'
$secureSecret = Read-Host 'Google OAuth client secret' -AsSecureString
if ($secureSecret.Length -eq 0) { throw 'Google OAuth client secret is required.' }
try { $protectedSecret = $secureSecret | ConvertFrom-SecureString }
catch { throw 'Windows could not protect the OAuth client secret for this user profile. Run this command from your normal signed-in PowerShell session.' }

$protectedSecret | Set-Content -LiteralPath $secretPath -Encoding ascii -NoNewline
$protectedSecret = $null
Write-Host 'PASS  Installed OAuth client secret updated with Windows DPAPI.' -ForegroundColor Green
Write-Host 'Stop and reopen GPOS Helper before authenticating again.'

