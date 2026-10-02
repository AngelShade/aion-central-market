# Central Market validation — 2026-10-02

This update is built from the shared standalone repository, with its existing remote documentation commits preserved. The server and client checks use the public source; no private marketplace, quest, motion, graphics, or speech systems are required.

| Check | Result |
| --- | --- |
| Maven Commons/GameServer package | Passed with Java 25; build-time dependencies resolved normally |
| Isolated MySQL integration | 159 checks passed; fresh empty test schema, structure-only fixture tables, no live player rows copied or changed |
| Real item catalog | 46,452 eligible templates; 5,589 special-inventory templates excluded |
| Order-list browser layout | 1024×768, 1920×1080, 2560×1440; no overflow; item/Kinah collection buttons in bounds |
| Text scaling | Order text 15px at 1024, 17px at 1920 and 2560 |
| Stock/demand controls | Stock selects Buy Now; demand-only prices place a preorder; selling selects the highest waiting bid |
| Standalone signed client package | 16 staged files; manifest source/staged hashes; original model key preserved; all three addon signatures verified |
| Native HUD hooks | Both real machine-code hooks and compiled callback executed in fixture memory; registers/stack preserved, other commands and Shop passed through; placement verified at four UI scales |
| Native archive preservation | All unchanged entries retained, both HUD layouts and English locale patched |
| Client install/restore | Disposable client fixture; install, backups, later-change rejection and exact restore |
| Source kit | CRC and SHA-256 checks; excludes client archives, DLLs, JARs, databases, signing keys and extracted item pictures |

The database checks exercise immediate fills, escrow, price improvement, cancellation, partial purchases, per-order item/Kinah collection, tax rounding, rollback, self-trade exclusion, equal-bid priority, simulation, exact item custody, and idempotent legacy-settlement migration.

During development, an 80-sample matching-lock probe measured every wait below 1ms after the fair-lock fix. This measures contention on the server lock, not end-to-end game click latency. Browser layout checks use a fixture; item pictures are supplied by the native client at runtime. The original-client icon bridge previously passed real Awesomium coverage for all 3,588 textures and the legacy 40×40 padding exceptions.

## Reproduce the focused checks

Build the full repository with the upstream Maven instructions. Compile `CentralMarketDatabaseCheck.java` and `CentralMarketCatalogCheck.java` against the newly built server and library classpath. Run the database check from the repository root, passing a deployed GameServer directory for its database settings. It creates and removes its own isolated schema; the database user must be able to create a test schema. Run the catalog check from `game-server`, passing the real item_templates.xml path, so `config/central-market/media/icon_sources.tsv` resolves correctly.

Prepare a client package with `client-mods/central-market/build_package.py` using the supported original client, then run:

```powershell
python client-mods/market-shortcut/verify_native.py 'C:\Market-Staged'
python client-mods/market-shortcut/verify_package.py 'C:\Market-Staged'
java client-mods/market-shortcut/VerifySignatures.java 'C:\Market-Staged' 'C:\Aion'
```

Python native checks require `capstone`; package preparation and archive checks require `Pillow`. The package check uses disposable files and models a closed client; it does not alter the installed game.

After source integration, refresh the source kit and checksum list:

```powershell
python game-server/tools/export_central_market.py --output 'C:\Central-Market-Implementation.zip'
```

Stage new and edited source files before exporting the Git patch. The kit and its checksums use Git's canonical file bytes, keeping LF/CRLF checkouts reproducible. Use `--check` to verify the checksum list when rebuilding the archive without source changes.

## Remaining in-game acceptance

After deploying on the recipient's installation, verify opening from the HUD after login load; buying stocked quantities and collecting items; selling into waiting bids and collecting Kinah; partial-fill cancellation; notifications; transfer/withdrawal; and persistence after relogin. Confirm item attributes, resolution changes, text readability and actual click response in the embedded Aion browser. Automated checks do not establish this final in-game acceptance.
