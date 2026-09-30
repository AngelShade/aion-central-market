param(
    [Parameter(Mandatory = $true)][string]$ClientPath,
    [Parameter(Mandatory = $true)][string]$BackupPath
)
$ErrorActionPreference = 'Stop'
if (Get-Process -Name 'aion.bin' -ErrorAction SilentlyContinue) { throw 'Fully close Aion before restoring.' }
$clientRoot = (Resolve-Path -LiteralPath $ClientPath).Path
$backup = (Resolve-Path -LiteralPath $BackupPath).Path
$backupRoot = Join-Path $clientRoot 'TransmogMenu-backups'
if (-not $backup.StartsWith($backupRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Backup must be inside this client backup directory.' }
$manifest = Get-Content -Raw -LiteralPath (Join-Path $backup 'manifest.json') | ConvertFrom-Json
if ($manifest.clientRoot -ne $clientRoot) { throw 'Backup belongs to a different client.' }
$expected = @('bin64/game.dll', 'bin32/bin32.pak.sig', 'Data/func_pet/func_pet.pak.sig', 'Plugin/RelicCalc/RelicCalc.pak', 'Plugin/RelicCalc/RelicCalc.pak.sig', 'Pub.key')
if ($manifest.signatureIsolation -in 'plugin-v1','archive-v2') { $expected += @('bin64/crysystem.dll', 'Addon.key') }
if ($manifest.signatureRepair) { $expected = @($expected | Where-Object { $_ -notin @('bin64/game.dll', 'Plugin/RelicCalc/RelicCalc.pak') }) }
if ($manifest.inventorySlots -in 180,279) { $expected += 'Data/ui/game/game.pak' }
if ($manifest.inventorySlots -in 180,279 -and $manifest.files.path -contains 'L10N/enu/data/data.pak') { $expected += 'L10N/enu/data/data.pak' }
if ($manifest.nativeIcons) { $expected += @('bin64/AionIconBridge.dll', 'bin64/AionIconBridge.index') }
if (-not $manifest.signatureIsolation -and @($manifest.files).Count -eq 5) { $expected = @($expected | Where-Object { $_ -ne 'bin64/game.dll' }) }
if (@($manifest.files).Count -ne $expected.Count -or (Compare-Object ($manifest.files.path | Sort-Object) ($expected | Sort-Object))) { throw 'Unexpected backup file list.' }
foreach ($entry in $manifest.files) {
    if ($null -ne $entry.original -and (Get-FileHash -LiteralPath (Join-Path $backup $entry.path)).Hash -ne $entry.original) { throw "Backup mismatch: $($entry.path)" }
    if ($null -eq $entry.original -and -not (Test-Path -LiteralPath (Join-Path $clientRoot $entry.path))) { continue }
    $current = (Get-FileHash -LiteralPath (Join-Path $clientRoot $entry.path)).Hash
    if ($current -ne $entry.staged -and $current -ne $entry.original) { throw "Client file changed after installation: $($entry.path)" }
}
$legacySaved = [IO.Path]::GetFullPath((Join-Path $backup 'legacy-TransmogMenu'))
foreach ($entry in $manifest.retiredFiles) {
    if ($entry.path -ne 'bin64/game.dll.patched') { throw 'Unexpected pending file in backup.' }
    if ((Get-FileHash -LiteralPath (Join-Path $backup $entry.path)).Hash -ne $entry.sha256) { throw 'Pending DLL backup mismatch.' }
    if (Test-Path -LiteralPath (Join-Path $clientRoot $entry.path)) { throw 'Another pending DLL exists; preserve it before restoring.' }
}
$legacy = [IO.Path]::GetFullPath((Join-Path $clientRoot 'Plugin\TransmogMenu'))
if (-not $legacy.StartsWith($clientRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or
    -not $legacySaved.StartsWith($backup + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid legacy addon restore paths.' }
if ((Test-Path -LiteralPath $legacySaved) -and (Test-Path -LiteralPath $legacy)) { throw 'A new TransmogMenu directory exists; preserve it before restoring.' }
foreach ($entry in $manifest.files | Sort-Object { $_.path -eq 'Pub.key' }) {
    if ($null -eq $entry.original) {
        $addedFile = Join-Path $clientRoot $entry.path
        if (Test-Path -LiteralPath $addedFile) { Remove-Item -LiteralPath $addedFile }
        continue
    }
    Copy-Item -LiteralPath (Join-Path $backup $entry.path) -Destination (Join-Path $clientRoot $entry.path)
    if ((Get-FileHash -LiteralPath (Join-Path $clientRoot $entry.path)).Hash -ne $entry.original) { throw 'Restore verification failed.' }
}
if (Test-Path -LiteralPath $legacySaved) { Move-Item -LiteralPath $legacySaved -Destination $legacy }
foreach ($entry in $manifest.retiredFiles) {
    Copy-Item -LiteralPath (Join-Path $backup $entry.path) -Destination (Join-Path $clientRoot $entry.path)
}
Write-Output 'Restored the files recorded by this backup, including the DLL when present.'
