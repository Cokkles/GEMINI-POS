[CmdletBinding()]
param(
    [switch]$IncludePackage,
    [switch]$NoRestore
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$helperTests = Join-Path $root 'tests\Gpos.Helper.Tests\Gpos.Helper.Tests.csproj'
$helperProject = Join-Path $root 'src\Gpos.Helper\Gpos.Helper.csproj'
$clientTest = Join-Path $root 'client\gpos-helper-client.test.mjs'
$bridgeTest = Join-Path $root 'client\gpos-helper-aegis-bridge.test.mjs'

dotnet run --configuration Release --project $helperTests
if ($LASTEXITCODE -ne 0) { throw "Helper tests failed with exit code $LASTEXITCODE." }

node $clientTest
if ($LASTEXITCODE -ne 0) { throw "Browser client tests failed with exit code $LASTEXITCODE." }

node $bridgeTest
if ($LASTEXITCODE -ne 0) { throw "AEGIS bridge tests failed with exit code $LASTEXITCODE." }

if ($IncludePackage) {
    if (-not $NoRestore) {
        dotnet restore $helperProject -r win-x64 -p:NuGetAudit=false
        if ($LASTEXITCODE -ne 0) { throw "Windows runtime restore failed with exit code $LASTEXITCODE." }
    }
    & (Join-Path $PSScriptRoot 'build-windows.ps1') -NoRestore
}

Write-Host 'RESULT: ALL GPOS HELPER VALIDATION PASSED' -ForegroundColor Green

