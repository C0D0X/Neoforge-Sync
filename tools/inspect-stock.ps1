param(
    [Parameter(Mandatory = $true)]
    [string] $Instance
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $Instance).Path
$manifest = Get-Content -LiteralPath (Join-Path $root 'stock\manifest.json') -Raw | ConvertFrom-Json
$instanceInfo = Get-Content -LiteralPath (Join-Path $root 'minecraftinstance.json') -Raw | ConvertFrom-Json
$modsDir = Join-Path $root 'mods'
$overridesDir = Join-Path $root 'stock\overrides\mods'

if (-not $manifest.files -or -not $instanceInfo.installedAddons) {
    throw 'The stock manifest or CurseForge installed-addon metadata is empty.'
}

$addonsById = @{}
foreach ($addon in $instanceInfo.installedAddons) {
    if ($null -ne $addon.addonID -and $null -ne $addon.installedFile.id) {
        $key = "$($addon.addonID):$($addon.installedFile.id)"
        if (-not $addonsById.ContainsKey($key)) { $addonsById[$key] = @() }
        $addonsById[$key] += $addon
    }
}

$stockNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$unresolved = [System.Collections.Generic.List[string]]::new()
$nonMods = 0
foreach ($entry in $manifest.files) {
    $key = "$($entry.projectID):$($entry.fileID)"
    if (-not $addonsById.ContainsKey($key) -or $addonsById[$key].Count -ne 1) {
        $unresolved.Add($key)
        continue
    }
    $addon = $addonsById[$key][0]
    if ($addon.categorySection.path -in @('resourcepacks', 'shaderpacks', 'datapacks')) {
        $nonMods++
        continue
    }
    if ($addon.categorySection.path -ne 'mods') {
        $unresolved.Add($key)
        continue
    }
    $name = $addon.fileNameOnDisk
    if (-not $name -or [IO.Path]::GetFileName($name) -ne $name -or -not (Test-Path -LiteralPath (Join-Path $modsDir $name) -PathType Leaf)) {
        $unresolved.Add($key)
        continue
    }
    [void] $stockNames.Add($name)
}

if (Test-Path -LiteralPath $overridesDir -PathType Container) {
    Get-ChildItem -LiteralPath $overridesDir -File | ForEach-Object { [void] $stockNames.Add($_.Name) }
}
$unknown = @(Get-ChildItem -LiteralPath $modsDir -File -Filter '*.jar' | Where-Object { -not $stockNames.Contains($_.Name) })

Write-Output "Stock pack: $($manifest.name) $($manifest.version)"
Write-Output "Manifest entries: $($manifest.files.Count)"
Write-Output "Identified non-mod entries: $nonMods"
Write-Output "Unresolved manifest entries: $($unresolved.Count)"
Write-Output "JARs not identified by manifest or overrides: $($unknown.Count)"
if ($unresolved.Count -gt 0) {
    Write-Output 'Unresolved project:file IDs:'
    $unresolved | ForEach-Object { Write-Output "  $_" }
}
if ($unknown.Count -gt 0) {
    Write-Output 'Unidentified JARs (not automatically classified as additions):'
    $unknown | ForEach-Object { Write-Output "  $($_.Name)" }
}
