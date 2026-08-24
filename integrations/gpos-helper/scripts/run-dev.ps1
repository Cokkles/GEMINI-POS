[CmdletBinding()]
param([int]$Port = 47831)

$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot '..\src\Gpos.Helper\Gpos.Helper.csproj'
$env:GPOS_Helper__DevelopmentMode = 'true'
$env:GPOS_Helper__LaunchBrowser = 'false'
$env:GPOS_Helper__Port = [string]$Port
dotnet run --project $project

