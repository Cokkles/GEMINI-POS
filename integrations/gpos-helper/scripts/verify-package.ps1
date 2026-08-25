[CmdletBinding()]
param([string]$ArtifactDirectory = (Join-Path $PSScriptRoot '..\dist\win-x64'))

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ArtifactDirectory).Path
$manifestPath = Join-Path $root 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Package manifest is missing.' }

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.service -ne 'gpos-helper' -or $manifest.runtime -ne 'win-x64') { throw 'Package manifest identity is invalid.' }
if ($null -eq $manifest.files -or $manifest.files.Count -eq 0) { throw 'Package manifest has no payload entries.' }

$rootPrefix = $root.TrimEnd('\') + '\'
$expected = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $manifest.files) {
    $relative = [string]$entry.path
    if ([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative)) { throw "Unsafe manifest path: $relative" }
    $target = [IO.Path]::GetFullPath((Join-Path $root $relative.Replace('/', '\')))
    if (-not $target.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Manifest path escapes package: $relative" }
    if (-not $expected.Add($target)) { throw "Duplicate manifest path: $relative" }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "Package file is missing: $relative" }
    $file = Get-Item -LiteralPath $target
    if ($file.Length -ne [long]$entry.bytes) { throw "Package size mismatch: $relative" }
    $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($hash -ne [string]$entry.sha256) { throw "Package hash mismatch: $relative" }
}

$actual = @(Get-ChildItem -LiteralPath $root -File -Recurse | Where-Object { $_.FullName -ne $manifestPath })
$unexpected = @($actual | Where-Object { -not $expected.Contains($_.FullName) })
if ($unexpected.Count -gt 0) { throw "Unexpected package file: $($unexpected[0].FullName.Substring($rootPrefix.Length))" }
if ($actual.Count -ne $expected.Count) { throw 'Package payload count does not match the manifest.' }

Write-Host "PASS  package manifest verified ($($expected.Count) files)"

