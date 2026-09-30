# Aion 4.8 Central Market

This repository adds an account-wide Central Market and combined Warehouse window to the [Beyond Aion 4.8 server emulator](https://github.com/beyond-aion/aion-server). The branch starts at upstream commit `267ce6033f39e8d297d2ac2657e5a6e930723578`; [the original README](docs/UPSTREAM_README.md) and GPL-3.0 license are retained.

The market uses Aion items and Kinah. Players can move items between Inventory, Character Warehouse, Account Warehouse, and Market Warehouse; deposit Kinah; place buy and sale orders; cancel unfilled orders; and collect taxed proceeds. Items remain real inventory rows with their enchantment, sockets, appearance, and other attributes. The existing Broker stays separate. See [market rules and behavior](docs/CENTRAL_MARKET.md).

## What is included

- `game-server/src/.../CentralMarket*`: transactional custody, matching, account-bound HTTP requests, and market rules.
- `game-server/config/central-market`: the database schema, browser HTML/CSS/JavaScript, and an item-to-icon mapping.
- `client-mods/central-market`: source for a version-checked client menu/browser patch, archive signing, installation, restoration, and icon extraction.
- Focused changes to inventory persistence, storage type, packet serialization, GameServer startup/shutdown, and configuration.
- Catalog and isolated database checks in `game-server/test/...`.

No original client executable, signed client package, extracted icon PNGs, player database, credentials, or compiled server JAR are committed. The icon PNGs must be generated from the recipient's own matching Aion client before enabling the market.

## Requirements

- Java 25, Maven, and MySQL or MariaDB for this emulator source.
- The original English **Aion 4.8 NA 64-bit client**. The client patch checks the exact original `bin64/game.dll` SHA-256: `5334cf2164468678e45fe1a5decf58a0fbc4fd7f22cfdcbb87d28edce8d2c11c`. It also checks the original `crysystem.dll` before patching. Other client builds need their native offsets and behavior verified separately.
- Python 3.10+ for the client builder. Icon extraction additionally needs Pillow (`python -m pip install Pillow`).
- The included client URL is `http://127.0.0.1:8091/market`, so client and GameServer must run on the **same computer**. Remote play requires adapting the native URL allowlist, menu URL, listener address, and transport/security together.

## Build and prepare

1. Build the server from this repository using the [upstream Maven instructions](docs/UPSTREAM_README.md#building). Do not deploy the new JAR while players are connected.
2. Find the original client's item artwork PAK file(s), then generate icons into the source market media directory:

   ```powershell
   python client-mods/central-market/extract_icons.py --items-pak 'C:\Aion\Data\Items\Items.pak'
   ```

   Repeat `--items-pak` for additional item artwork PAKs if the client stores them separately. The script checks that every mapped icon was found. The generated `game-server/config/central-market/media/icons/` directory is intentionally Git-ignored. Copy it with the rest of `config/central-market` to the **running** GameServer folder. Market startup refuses missing icons.
3. With Aion fully closed, prepare a signed package from the exact original client. The output must be outside the client directory and must not already exist:

   ```powershell
   python client-mods/central-market/build_package.py --client-path 'C:\Aion' --java 'C:\Path\To\JDK25\bin\java.exe' --output 'C:\Market-Staged'
   ```

   Review `C:\Market-Staged\manifest.json`, then install it on the same client:

   ```powershell
   ./client-mods/central-market/Install.ps1 -ClientPath 'C:\Aion' -PreparedPath 'C:\Market-Staged'
   ```

   The installer checks hashes, backs up replaced files, and prints the backup path. To restore the client later, close Aion and run `Restore.ps1` with that path. The builder adds only the Central Market menu; it does not add Transmog, a private Cash Shop, or a unified inventory layout. It expects an unmodified original client and `RelicCalc` addon.

## Database and server deployment

1. Back up the database and running GameServer. When market activity exists, keep `inventory`, `item_stones`, and all nine `central_market_*` tables in the same consistent backup.
2. Copy `game-server/config/central-market` including the generated icons to `config/central-market` under the running GameServer directory. On startup, the server applies `schema.sql` using `CREATE TABLE IF NOT EXISTS` and verifies InnoDB. The optional `game-server/tools/CentralMarketSchemaInstaller.java` can apply and verify it before startup using the deployed database settings.
3. Set `gameserver.centralmarket.enable = true` in the running server's `config/mygs.properties`. The defaults are bind `127.0.0.1` and port `8091`, matching the client URL.
4. Stop GameServer normally with no players online, deploy the newly built JAR, and restart. Confirm `Central Market ready` and `Central Market listening` in its log. A direct unauthenticated `/market/state` request returning 403 is expected.
5. In Aion, open **Central Market** from Additional Functions. Verify storage and Kinah transfers, then buy/sell and cancellation with two different accounts. Check item attributes, collection, and relogin persistence.

Market items use account-owned inventory location `125`. Do not roll back to an older JAR after trading without reconciling market custody and escrow. Client restoration only restores client files; it does not undo trades.

## Validation scope

The new Java sources compile against the local Aion 4.8 classes, and the catalog check found 46,452 eligible real templates while excluding 5,589 special-inventory templates. A full Maven build of this isolated checkout could not run because the Git commit ID Maven plugin was not cached and outbound Maven Central access was blocked. The client package builder and live market transactions still need verification on the recipient's installation.
