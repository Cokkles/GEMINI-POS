[CmdletBinding()]
param([string]$Configuration = 'Release')

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$project = Join-Path $root 'src\Gpos.Helper\Gpos.Helper.csproj'
$output = Join-Path $root 'dist\win-x64'
dotnet publish $project -c $Configuration -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $output
Write-Host "Built: $(Join-Path $output 'gpos-helper.exe')"

