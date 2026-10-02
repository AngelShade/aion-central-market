# Aion 4.8 Server Emulator - Central Market

## Based and works on https://github.com/beyond-aion/aion-server

This repository adds an account-wide Central Market and combined Warehouse window to the [Beyond Aion 4.8 server emulator](https://github.com/beyond-aion/aion-server). The branch starts at upstream commit `267ce6033f39e8d297d2ac2657e5a6e930723578`; [the original README](docs/UPSTREAM_README.md) and GPL-3.0 license are retained.

The market uses Aion items and Kinah. Players can move items between Inventory, Character Warehouse, Account Warehouse, and Market Warehouse; deposit Kinah; place buy and sale orders; cancel unfilled orders; and collect items or taxed Kinah beside each order. A configurable simulation defaults to 3,000 traders for solo servers. Player-sold items remain real inventory rows with their enchantment, sockets, appearance, and other attributes. The existing Broker stays separate. See [market rules and behavior](docs/CENTRAL_MARKET.md).

## What is included
https://www.youtube.com/watch?v=fYqK6Y1Bazg
- `game-server/src/.../CentralMarket*`: transactional custody, matching, account-bound HTTP requests, and market rules.
- `game-server/config/central-market`: the database schema, browser HTML/CSS/JavaScript, and an item-to-icon mapping.
- `client-mods/central-market`: source for a version-checked client menu/browser patch, archive signing, installation, restoration, and a native client icon bridge.
- Focused changes to inventory persistence, storage type, packet serialization, GameServer startup/shutdown, and configuration.
- Catalog and isolated database checks in `game-server/test/...`.
- `client-mods/market-shortcut`: standalone HUD shortcut and generated scales artwork.
- An optional hash-checked server update builder/installer in `game-server/tools`.

No original client executable, signed client package, extracted icon PNGs, player database, credentials, or compiled server JAR are committed. The native icon bridge reads the recipient's unchanged Items.pak directly; no PNG extraction is needed.

## Requirements

- Java 25, Maven, and MySQL or MariaDB for this emulator source.
- The original English **Aion 4.8 NA 64-bit client**. The client patch checks the exact original `bin64/game.dll` SHA-256: `5334cf2164468678e45fe1a5decf58a0fbc4fd7f22cfdcbb87d28edce8d2c11c`. It also checks the original `crysystem.dll` before patching. Other client builds need their native offsets and behavior verified separately.
- Python 3.12 and Visual Studio 2022 C++ Build Tools + Windows SDK for the client builder. The HUD builder and isolated bridge test use Pillow (`python -m pip install Pillow`).
- The included client URL is `http://127.0.0.1:8091/market`, so client and GameServer must run on the **same computer**. Remote play requires adapting the native URL allowlist, menu URL, listener address, and transport/security together.

## Build and prepare

1. Build the server from this repository using the [upstream Maven instructions](docs/UPSTREAM_README.md#building). Do not deploy the new JAR while players are connected.
2. Copy `game-server/config/central-market` into the running server configuration. Keep `icon_sources.tsv`: it is catalog metadata, independent of image files. The client builder creates a small item/texture index from the recipient's original `Data/Items/Items.pak` and compiles the native bridge. No item PNGs are installed or served. See `client-mods/native-icon-bridge/README.md`.
3. With Aion fully closed, prepare a signed package from the exact original client. Save the verified original Game.dll as bin64/game.dll.orig before patching. The output must be outside the client directory and must not already exist:

   ```powershell
   python client-mods/central-market/build_package.py --client-path 'C:\Aion' --java 'C:\Path\To\JDK25\bin\java.exe' --output 'C:\Market-Staged'
   ```

   Review `C:\Market-Staged\manifest.json`, then install it on the same client:

   ```powershell
   ./client-mods/central-market/Install.ps1 -ClientPath 'C:\Aion' -PreparedPath 'C:\Market-Staged'
   ```

   The installer checks hashes, backs up replaced files, and prints the backup path. To restore the client later, close Aion and run `Restore.ps1` with that path. The builder adds the Central Market HUD icon beside Shop and its browser; it does not add Transmog, a private Cash Shop, or a unified inventory layout. It expects the supported original client and `RelicCalc` addon. `--menu-only` retains the earlier Additional Functions entry. The HUD variant stages 16 replacements, all covered by the same installer and restore checks.

## Database and server deployment

1. Back up the database and running GameServer. When market activity exists, keep `inventory`, `item_stones`, and all eleven `central_market_*` tables in the same consistent backup.
2. Copy `game-server/config/central-market` including the item-ID mapping and browser assets to `config/central-market` under the running GameServer directory, and copy `game-server/config/main/central-market-simulation.properties` to `config/main`. On startup, the server applies `schema.sql` using `CREATE TABLE IF NOT EXISTS` and verifies InnoDB. The optional `game-server/tools/CentralMarketSchemaInstaller.java` can apply and verify it before startup using the deployed database settings.
3. Set `gameserver.centralmarket.enable = true` in the running server's `config/mygs.properties`. The defaults are bind `127.0.0.1` and port `8091`, matching the client URL.
4. Stop GameServer normally with no players online, deploy the newly built JAR, and restart. Confirm `Central Market ready` and `Central Market listening` in its log. A direct unauthenticated `/market/state` request returning 403 is expected.
5. In Aion, open **Central Market** using the HUD icon beside Shop after login loading finishes (or Additional Functions for `--menu-only`). Verify storage and Kinah transfers, then buy/sell and cancellation with two different accounts. Check item attributes, collection, and relogin persistence.

Market items use account-owned inventory location `125`. Do not roll back to an older JAR after trading without reconciling market custody and escrow. Client restoration only restores client files; it does not undo trades.

## Latest update — 2026-10-02

- Solo trading with 3,000 simulated traders; stock matches before virtual quotes rotate.
- Stock purchases fill immediately; unfilled quantities stay funded as preorders. The order book labels available sale stock and waiting buyers separately.
- **Collect Items** and **Collect Kinah** beside individual orders, including partial fills and safe legacy migration.
- Notifications for your recent purchases and sales, linked to My Orders.
- Fair matching locks, bounded maintenance, quote reuse, lightweight polling, and independent warehouse/catalog reads.
- Raised buttons, larger text on large viewports, and a standalone HUD icon beside Shop.

## Validation scope

The updated shared repository passes the full Commons/GameServer Maven package build, **159 isolated database checks**, and the real-template catalog check (**46,452 eligible**, **5,589 special-inventory exclusions**). Browser checks at 1024×768, 1920×1080 and 2560×1440 found no order-list overflow and kept collection controls in bounds. See [the validation record](docs/CENTRAL_MARKET_VALIDATION.md) for client-package checks and the remaining in-game acceptance steps.

The original-client icon bridge previously passed real Awesomium coverage for all 3,588 textures and legacy padding exceptions. Item artwork continues to come from the recipient's unchanged Items.pak. Test transfers, immediate fills, collection, notifications and relogin persistence on the recipient's installation after deployment.
