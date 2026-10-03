# Central Market changelog

This records Central Market changes on top of Beyond Aion 4.8, starting at upstream `267ce6033f39e8d297d2ac2657e5a6e930723578`. Entries describe repository changes; see [validation](docs/CENTRAL_MARKET_VALIDATION.md) for automated coverage and remaining in-game acceptance. Add an entry with each future market change, including its behavior, migration requirements and checks.

## 2026-10-03 — Categories, quantity defaults, warehouse responsiveness and reconnect

Included in the commit containing this entry; previous release: [`6bd7c8db6`](https://github.com/AngelShade/aion-central-market/commit/6bd7c8db6).

### Added

- Expandable category navigation with item types underneath, classified from Aion 4.8 templates.
- Combined item-level, base-price, grade and armor-slot filters; lowest/highest price, stock, traded quantity and price-movement sorting before pagination.
- Opening Price Changes results: changed base prices updated within 24 hours, ranked by absolute percentage movement. Rows show listed/traded quantities and current/previous-price differences.
- Framed **Always Max** option shared by Transfer Item and Register Sale and saved per account. Purchases still default to one.
- **Transfer Market Warehouse** batch confirmation when all selected Inventory/Character/Account stacks are eligible and their full quantities fit available market volume.
- Current native web-session token sent on each world entry, supporting reconnect from server selection after a GameServer restart.

### Changed and fixed

- Market and Market Warehouse are selected when the window opens.
- Warehouse tabs use cached local panes; native icons load in small batches. Hidden activity views render on demand and unchanged content is retained.
- Sale escrow is removed from the warehouse grid. Cancelling a sale returns its unsold remainder.
- My Orders keeps active/queued orders and uncollected items/proceeds, and hides fully settled closed orders. Trade and collection history is retained.
- Failed preference saves restore the confirmed checkbox state and refresh the request token for retry.
- The focused server builder includes browse, preference and world-entry session classes. Documentation and source-kit checksums cover this release.

### Upgrade

- Deploy the updated server, `schema.sql`, and all three browser files together. Startup adds `central_market_preferences`; all twelve market tables, inventory and item stones belong in one consistent backup.
- Existing standalone HUD/browser installations need no additional client patch for these changes. The source client builder still targets the documented original Aion 4.8 NA client.
- Reconnect behavior and final game responsiveness require a live recipient-client check. Intermittent embedded webpage flashing remains unresolved.

## 2026-10-02 — Solo simulation, per-order collection, performance and HUD

Commit: [`6bd7c8db6`](https://github.com/AngelShade/aion-central-market/commit/6bd7c8db6).

- Configurable solo simulation, defaulting to 3,000 traders, without simulated-to-simulated trades or generated modified gear.
- Immediate purchases from sale stock and funded preorders for unfilled quantities; stock and waiting-demand labels clarified.
- Per-order Collect Items/Collect Kinah, partial-fill collection, purchase custody and idempotent legacy-settlement migration.
- Purchase/sale notifications linked to My Orders.
- Fair matching locks, bounded quote maintenance, quote reuse, independent warehouse/catalog reads and lightweight activity polling.
- Multi-item transactional transfer with capacity, eligibility and rollback checks; quest and dedicated special-inventory items excluded.
- Raised buttons, viewport-scaled text and a generated Central Market HUD icon beside Shop.
- Focused hash-checked server staging/installer, source integration kit and validation record.

## 2026-10-01 — Original client artwork and documentation

Feature commits: [`1d604e810`](https://github.com/AngelShade/aion-central-market/commit/1d604e810), [`de3f62975`](https://github.com/AngelShade/aion-central-market/commit/de3f62975).

- Native icon bridge reads original Items.pak textures directly, replacing extracted/server-served item PNGs.
- Client texture index and native bridge builder, with Awesomium decoding checks and legacy padded-icon coverage.
- Source-only publication preserves the recipient's client assets and signing requirements.
- Market documentation was streamlined in [`da9124213`](https://github.com/AngelShade/aion-central-market/commit/da9124213). README presentation, upstream compatibility and video/reference updates followed in [`2191b5ec1`](https://github.com/AngelShade/aion-central-market/commit/2191b5ec1), [`1670e4005`](https://github.com/AngelShade/aion-central-market/commit/1670e4005), [`ea8085f35`](https://github.com/AngelShade/aion-central-market/commit/ea8085f35), and [`4a629bd0a`](https://github.com/AngelShade/aion-central-market/commit/4a629bd0a).

## 2026-09-30 — Initial standalone Central Market

Commit: [`62ffdc366`](https://github.com/AngelShade/aion-central-market/commit/62ffdc366).

- Account-wide Market Warehouse, combined character/account/inventory transfers and Kinah deposit/withdrawal.
- Transactional sale custody, funded buy orders, partial matching, cancellation, tax and collection, separate from the Broker economy.
- Aion item attributes retained with real inventory rows, account ownership checks and retry receipts.
- Market browser, search, Favorites, saved searches, item details/preview and trade history.
- Initial InnoDB schema, emulator configuration, persistence/packet integration and isolated catalog/database checks.
- Standalone version-checked client menu/browser integration with signed packages, hash-checked installation, backups and restoration.
