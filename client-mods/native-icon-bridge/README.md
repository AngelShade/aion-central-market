# Original client icons for Cash Shop and Central Market

This optional bridge serves the existing HTML item-image requests directly from
the original client's `Data/Items/Items.pak`. It is specific to the verified
**Aion 4.8 NA x64** client and Awesomium DLL. It uses Awesomium's native resource
callback; the browser still displays PNG images, generated in memory from the
client DDS textures. It does not use the broker's UI drawing API.

No extracted artwork is installed. The generated index contains item IDs,
texture offsets and decoding metadata. The bridge verifies the original archive
hash at startup, validates decompressed size/CRC, and caches at most 512 unique
textures in memory. Items sharing a texture share a cache entry.

Only `http://127.0.0.1:<port>/shop/media/icons/<item-id>.png` and
`/market/media/icons/<item-id>.png` are intercepted. Missing icons and unmatched
requests continue through the previous callback and normal HTTP loading.
There are no item PNGs on the server. An unmatched/missing item image reaches
HTTP and returns 404; a matching native bridge is required for item images.
The market's item-ID catalog metadata is still required.
There are no server protocol or database changes.

## Prepare and install

Use the standalone Central Market builder supplied with this package. It enables
the icon bridge without Inventory or warehouse expansion patches.

Requirements: Python 3.12, Java for the
existing archive signer, and Visual Studio 2022 C++ Build Tools with the Windows
SDK. `build_bridge.py` uses `/MT` so the bridge needs no separate MSVC runtime
installation. The client must have its verified `bin64/game.dll.orig`.

```powershell
python client-mods/central-market/build_package.py `
  --client-path '<Aion 4.8 NA root>' `
  --java '<java.exe>' `
  --output '<new stage outside client>'

# Close all Aion clients, then install the entire combined package.
& client-mods/central-market/Install.ps1 `
  -ClientPath '<Aion 4.8 NA root>' -PreparedPath '<stage>'
```

The builder creates `bin64/AionIconBridge.dll`, `bin64/AionIconBridge.index`,
and a Game.dll import/initialization hook that runs before Lua queues browser
creation. The installer checks both original inputs and staged hashes, and backs
up the current client files. Compiler intermediates stay outside the payload.
Do not copy only the Game.dll; it requires its matching bridge DLL.

Restore with the existing `client-mods/central-market/Restore.ps1`, using the backup path
printed by the installer. It restores the previous Game.dll and removes bridge
files that were newly added by that installation.

## Verify

The isolated test uses the client's actual Awesomium browser. Prepare a disposable
root containing `bin64/AionIconBridge.dll`, `bin64/AionIconBridge.index`, and an
unchanged copy of `Data/Items/Items.pak`, then run:

```powershell
python client-mods/native-icon-bridge/verify_bridge.py `
  --root '<disposable root>' --browser-bin '<original client root>/bin64'
```

It requires Pillow. It decodes every indexed texture, checks both shop routes
without server image downloads, verifies missing-image fallback, and tests
callback replacement plus view creation/destruction.

For the installed client, restart Aion and open both Cash Shop and Central
Market. Check icon appearance, search, paging and native hover tooltips.
`Logs/NativeIcons.<process-id>.log` records initialization, browser attachment,
and the first successful native image for each shop. These lines confirm the
actual game browser used the bridge; isolated tests alone do not prove that.
Inventory and both warehouse grids should retain their previous slots and
Search/Clear controls. This bridge does not expand storage.

## Sharing

Share these source files with the included Central Market client source and
instructions. Recipients build the index from their own matching original
client. Do not distribute the client archive or extracted artwork. A changed
client/Awesomium build needs fresh native hook verification before enabling the
bridge; unsupported builds need a verified bridge port before item images work.

The server URLs use `v=native-3` to invalidate earlier padded image caches.
Legacy DDS textures use the native client's top-left 40x40 sprite rectangle;
the `_64` variants use 64x64. Unused pixels can be opaque gray, so alpha bounds
alone are insufficient. Index version `AICON002` records the sprite rectangle.
The bridge clips that rectangle before removing transparent margins and
resizing in memory. Install the matching DLL and index together.
The isolated test compares Fresh Umblia and all ten legacy padding exceptions
with independently decoded 40x40 client artwork.
