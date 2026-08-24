[CmdletBinding()]
param([string]$Configuration = 'Release', [switch]$SkipSmoke, [switch]$NoRestore)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = Join-Path $root 'src\Gpos.Helper\Gpos.Helper.csproj'
$output = Join-Path $root 'dist\win-x64'
[xml]$projectXml = Get-Content -Raw $project
$version = [string]$projectXml.Project.PropertyGroup.Version
$publishArguments = @('publish', $project, '-c', $Configuration, '-r', 'win-x64', '--self-contained', 'true', '-p:PublishSingleFile=true', '-p:IncludeNativeLibrariesForSelfExtract=true', '-p:DebugType=None', '-o', $output)
if ($NoRestore) { $publishArguments += '--no-restore' }
dotnet @publishArguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $SkipSmoke) { & (Join-Path $PSScriptRoot 'smoke-windows.ps1') -ArtifactDirectory $output }
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$payload = @(Get-ChildItem -Path $output -File -Recurse | Where-Object { $_.Name -notlike 'smoke.*.log' -and $_.Name -ne 'manifest.json' } | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($output.Length + 1).Replace('\', '/')
    [ordered]@{ path = $relative; bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$manifest = [ordered]@{ service = 'gpos-helper'; version = $version; runtime = 'win-x64'; self_contained = $true; single_executable_process = $true; files = $payload }
$manifestPath = Join-Path $output 'manifest.json'
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8

$zip = Join-Path (Split-Path $output -Parent) "gpos-helper-$version-win-x64.zip"
Compress-Archive -Path (Join-Path $output '*') -DestinationPath $zip -Force
Write-Host "Built executable: $(Join-Path $output 'gpos-helper.exe')"
Write-Host "Built manifest:   $manifestPath"
Write-Host "Built package:    $zip"
