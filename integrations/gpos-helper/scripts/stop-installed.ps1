[CmdletBinding()]
param([int]$Port = 47831)

$ErrorActionPreference = 'Stop'
$expected = Join-Path $PSScriptRoot 'app\gpos-helper.exe'
$stopped = 0
Get-Process gpos-helper -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.Path -eq $expected) {
        Stop-Process -Id $_.Id
        $stopped++
    }
}

if ($stopped -eq 0) { Write-Host 'GPOS Helper is not running.' }
else { Write-Host 'GPOS Helper stopped.' }

