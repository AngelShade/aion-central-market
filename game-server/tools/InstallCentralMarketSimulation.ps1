param(
    [Parameter(Mandatory=$true)][string]$GameServerRoot,
    [int]$GamePort=7777
)
$ErrorActionPreference = 'Stop'
$serverRoot = (Resolve-Path -LiteralPath $GameServerRoot).Path.TrimEnd('\')
$packageRoot = $PSScriptRoot
# A running JVM may still load classes from its JAR. Install only after graceful shutdown.
if (& netstat -ano -p tcp | Select-String ":$GamePort\s+.*LISTENING") {
    throw "GameServer port $GamePort is listening. Stop GameServer gracefully before installing."
}
$entries = Get-Content -LiteralPath (Join-Path $packageRoot 'manifest.json') -Raw | ConvertFrom-Json
$allowed = @('libs/game-server-4.8-SNAPSHOT.jar','config/main/central-market-simulation.properties',
    'config/central-market/schema.sql','config/central-market/media/market.html','config/central-market/media/market.js','config/central-market/media/market.css')
if ($entries.Count -ne $allowed.Count) { throw 'Unexpected package manifest.' }
$seen = @{}
foreach ($entry in $entries) {
    if ($entry.path -notin $allowed -or $seen.ContainsKey($entry.path)) { throw 'Unexpected or duplicate package path.' }
    $seen[$entry.path] = $true
    $source = Join-Path (Join-Path $packageRoot 'payload') $entry.path
    $target = [IO.Path]::GetFullPath((Join-Path $serverRoot $entry.path))
    if (-not $target.StartsWith($serverRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Target escapes server root.' }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Package hash mismatch: $($entry.path)" }
    if ($entry.before) {
        if (-not (Test-Path -LiteralPath $target) -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $entry.before) {
            throw "Deployment changed since package build: $($entry.path). Rebuild the package first."
        }
    } elseif (Test-Path -LiteralPath $target) { throw "New file already exists: $($entry.path). Rebuild the package first." }
}
$backupRoot = Join-Path $serverRoot ('backups/central-market-simulation-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backupRoot | Out-Null
foreach ($entry in $entries) {
    if ($entry.before) {
        $backup = Join-Path $backupRoot $entry.path
        New-Item -ItemType Directory -Force -Path (Split-Path $backup) | Out-Null
        Copy-Item -LiteralPath (Join-Path $serverRoot $entry.path) -Destination $backup
        if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne $entry.before) { throw 'Backup verification failed.' }
    }
}
$changed = @()
try {
    foreach ($entry in $entries) {
        $target = Join-Path $serverRoot $entry.path
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        $changed += $entry
        Copy-Item -LiteralPath (Join-Path (Join-Path $packageRoot 'payload') $entry.path) -Destination $target -Force
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $entry.sha256) { throw 'Installed hash mismatch.' }
    }
} catch {
    foreach ($entry in $changed) {
        $target = Join-Path $serverRoot $entry.path
        if ($entry.before) { Copy-Item -LiteralPath (Join-Path $backupRoot $entry.path) -Destination $target -Force }
        elseif (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
    }
    throw
}
Write-Output "Central Market simulation installed. Backup: $backupRoot"
Write-Output 'Start GameServer normally. Startup should report simulation enabled with population 3000.'
