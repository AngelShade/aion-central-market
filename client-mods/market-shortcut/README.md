# Central Market HUD shortcut

The standalone client builder now adds the outlined scales icon immediately left of the native Shop icon. It calls the existing authenticated `/privatewarehouse` dispatcher. Placement follows the Shop container and current UI scale. Only clicks on `central_market_button` are consumed; the stock Shop and other HUD commands retain their normal behavior.

Both native HUD layouts and the English locale archive are patched. Normal, hover and pressed skins use the included original generated `assets/scales-outlined.png` artwork. Item pictures still come directly from the recipient's original Items.pak through the native icon bridge.

Use `client-mods/central-market/build_package.py` to prepare a package from the supported original client, and its `Install.ps1` / `Restore.ps1` for hash-checked installation, backups, and restoration. `--menu-only` retains the earlier Additional Functions entry. Install only with Aion closed. The standalone package does not include the private Cash Shop, graphics, speech, or launcher patches from the development installation.

`prepare.py` verifies every untouched archive entry while staging. The native binary patch verifies both original hook instructions before modifying them. Prepared output includes a manifest with source and staged hashes, hook records, and resource paths. Do not apply this standalone package over a different modified Game.dll; integrate and validate against that client's own patch history.
