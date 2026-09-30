param(
    [Parameter(Mandatory = $true)][string]$ClientPath,
    [Parameter(Mandatory = $true)][string]$PreparedPath
)
$ErrorActionPreference = 'Stop'
$clientRoot = (Resolve-Path -LiteralPath $ClientPath).Path
$prepared = (Resolve-Path -LiteralPath $PreparedPath).Path
if (Get-Process -Name 'aion.bin' -ErrorAction SilentlyContinue) {
    throw 'Fully close Aion before installing the signed menu files.'
}
$manifest = Get-Content -Raw -LiteralPath (Join-Path $prepared 'manifest.json') | ConvertFrom-Json
if ($manifest.clientRoot -ne $clientRoot) { throw 'Prepared files belong to a different client.' }
$expected = @('bin64/game.dll', 'bin32/bin32.pak.sig', 'Data/func_pet/func_pet.pak.sig', 'Plugin/RelicCalc/RelicCalc.pak', 'Plugin/RelicCalc/RelicCalc.pak.sig', 'Pub.key')
if ($manifest.signatureIsolation -in 'plugin-v1','archive-v2') { $expected += @('bin64/crysystem.dll', 'Addon.key') }
if ($manifest.signatureRepair) { $expected = @($expected | Where-Object { $_ -notin @('bin64/game.dll', 'Plugin/RelicCalc/RelicCalc.pak') }) }
if ($manifest.inventorySlots -in 180,279) { $expected += 'Data/ui/game/game.pak' }
if ($manifest.inventorySlots -in 180,279 -and $manifest.files.path -contains 'L10N/enu/data/data.pak') { $expected += 'L10N/enu/data/data.pak' }
if (@($manifest.files).Count -ne $expected.Count -or (Compare-Object ($manifest.files.path | Sort-Object) ($expected | Sort-Object))) {
    throw 'Unexpected replacement file list.'
}
foreach ($entry in $manifest.files) {
    $currentFile = Join-Path $clientRoot $entry.path
    $sourceMatches = if ($null -eq $entry.original) { -not (Test-Path -LiteralPath $currentFile) } else {
        (Test-Path -LiteralPath $currentFile) -and (Get-FileHash -LiteralPath $currentFile).Hash -eq $entry.original
    }
    if (-not $sourceMatches -or (Get-FileHash -LiteralPath (Join-Path $prepared $entry.path)).Hash -ne $entry.staged) {
        throw "File changed since preparation: $($entry.path)"
    }
}
$legacy = [IO.Path]::GetFullPath((Join-Path $clientRoot 'Plugin\TransmogMenu'))
$prefix = $clientRoot.TrimEnd('\') + '\'
if (-not $legacy.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid legacy addon path.' }
if (Test-Path -LiteralPath $legacy) {
    $legacyFiles = @(Get-ChildItem -LiteralPath $legacy -File -Recurse)
    if ($legacyFiles.Count -ne @($manifest.legacyAddon).Count) { throw 'Legacy addon changed since preparation.' }
    foreach ($entry in $manifest.legacyAddon) {
        $legacyFile = [IO.Path]::GetFullPath((Join-Path $legacy $entry.path))
        if (-not $legacyFile.StartsWith($legacy + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid legacy file path.' }
        if ((Get-FileHash -LiteralPath $legacyFile).Hash -ne $entry.sha256) { throw 'Legacy addon changed since preparation.' }
    }
}
$backup = [IO.Path]::GetFullPath((Join-Path $clientRoot ('TransmogMenu-backups\signed-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))))
if (-not $backup.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid backup path.' }
New-Item -ItemType Directory -Path $backup | Out-Null
foreach ($entry in $manifest.files) {
    if ($null -eq $entry.original) { continue }
    $saved = Join-Path $backup $entry.path
    New-Item -ItemType Directory -Path (Split-Path -Parent $saved) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $clientRoot $entry.path) -Destination $saved
    if ((Get-FileHash -LiteralPath $saved).Hash -ne $entry.original) { throw 'Backup verification failed.' }
}
Copy-Item -LiteralPath (Join-Path $prepared 'manifest.json') -Destination (Join-Path $backup 'manifest.json')
$legacySaved = Join-Path $backup 'legacy-TransmogMenu'
foreach ($entry in $manifest.retiredFiles) {
    if ($entry.path -ne 'bin64/game.dll.patched') { throw 'Unexpected pending file to retire.' }
    if ((Get-FileHash -LiteralPath (Join-Path $clientRoot $entry.path)).Hash -ne $entry.sha256) { throw 'Pending DLL changed since preparation.' }
    $saved = Join-Path $backup $entry.path
    Copy-Item -LiteralPath (Join-Path $clientRoot $entry.path) -Destination $saved
    if ((Get-FileHash -LiteralPath $saved).Hash -ne $entry.sha256) { throw 'Pending DLL backup failed.' }
}
try {
    foreach ($entry in $manifest.files | Sort-Object { $_.path -eq 'Pub.key' }) {
        Copy-Item -LiteralPath (Join-Path $prepared $entry.path) -Destination (Join-Path $clientRoot $entry.path)
        if ((Get-FileHash -LiteralPath (Join-Path $clientRoot $entry.path)).Hash -ne $entry.staged) { throw 'Install verification failed.' }
    }
    if (Test-Path -LiteralPath $legacy) { Move-Item -LiteralPath $legacy -Destination $legacySaved }
    foreach ($entry in $manifest.retiredFiles) {
        # Exact single file, already backed up and hash-verified above.
        Remove-Item -LiteralPath (Join-Path $clientRoot $entry.path)
    }
} catch {
    $installError = $_
    foreach ($entry in $manifest.files) {
        if ($null -eq $entry.original) {
            $addedFile = Join-Path $clientRoot $entry.path
            if (Test-Path -LiteralPath $addedFile) { Remove-Item -LiteralPath $addedFile }
        } else {
            Copy-Item -LiteralPath (Join-Path $backup $entry.path) -Destination (Join-Path $clientRoot $entry.path)
        }
    }
    if ((Test-Path -LiteralPath $legacySaved) -and -not (Test-Path -LiteralPath $legacy)) {
        Move-Item -LiteralPath $legacySaved -Destination $legacy
    }
    foreach ($entry in $manifest.retiredFiles) {
        Copy-Item -LiteralPath (Join-Path $backup $entry.path) -Destination (Join-Path $clientRoot $entry.path)
    }
    throw $installError
}
Write-Output "Installed and hash-verified $(@($manifest.files).Count) files. Backup: $backup"
Write-Output 'Start Aion and check Additional Functions for the configured server menus and Cash Shop.'
