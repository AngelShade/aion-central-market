# Central Market

The Central Market HUD shortcut opens one Aion window containing Inventory, Character Warehouse, Account Warehouse, Market Warehouse and Central Market. The existing Broker remains a separate economy.

## Using the window

1. Open **Central Market** using the HUD icon next to Shop. Allow the client to finish loading after login. The window starts on **Market**, with **Market Warehouse** selected.
2. Choose Inventory, Character or Account. Select an item and use **Transfer** or **Market Warehouse**. Double-click transfers an eligible item to Market Warehouse. Items can also be dragged onto a warehouse tab. Confirm the quantity and destination.
3. Deposit Kinah from the selected Inventory or warehouse. Buy orders use the Market Warehouse balance.
4. Search the market, choose an item and select its enchantment or tempering. **Available to buy** on the left means sale stock; **Waiting buyers** on the right means demand. The initial selection chooses the lowest stocked price in the current band. **Buy Now** fills stock at the selected price or lower; any remainder becomes a funded preorder. The confirmation updates when you choose another price.
5. To sell, select an unreserved item in Market Warehouse, then **Register Sale**. Listed items move into sale escrow and disappear from the warehouse grid. Unsold items return when the listing is cancelled.
6. **My Orders** shows active and queued orders, plus closed orders with items or Kinah still to collect. Fully settled orders leave this list; their trades and collections remain in **Trade History**. Cancel returns only the unfilled items or reserved Kinah.
7. Each order has **Collect Items** or **Collect Kinah** when it has a filled quantity. Collection also works for partial orders while the remainder stays active. Sale collection applies market tax and credits Market Warehouse Kinah. **Withdraw** transfers Kinah to the selected Inventory or warehouse.
8. Purchased items stay in account custody until **Collect Items** releases them into usable Market Warehouse stock. Transfer them to Inventory or a compatible warehouse. Previously delivered purchases remain usable; startup migration assigns only outstanding legacy proceeds to sales and cannot claim them twice.

Searches can be saved, items can be added to Favorites, and lists can be filtered, sorted and paged. Trade History includes purchases, sales and collections. Notifications show your 50 most recent purchases and sales, with links to My Orders, plus queued high-value listings. Item Details includes Aion stats and modified attributes; Item Preview opens the game's native preview window.

## Categories, filters and opening results

Categories expand in the left navigation to show their item types directly underneath. Classification uses the matching Aion 4.8 item groups, families and actions, including weapon types, armor materials, accessories, composite manastones, Stigmas, crafting designs, coins and medals, appearance items, pets, mounts, housing and boxes. Only populated categories appear; this does not change an item's trade eligibility or warehouse volume.

Combine search and type with **item level**, **base price in Kinah**, **grade**, **armor slot**, stock, Favorites or faction. Level ranges cover 0–65. The price filter uses the unenchanted item's base price; open Item Details for actual variant and order prices. Filters and sorting apply on the server before the 24-item page is selected. **Reset** clears search and ranges while retaining the selected category and type.

The opening **Price Changes** view shows base prices that differ from their previous price and were updated in the last 24 hours. It ranks the largest absolute percentage movement first, then item name and ID. Rows show current sale quantity, cumulative traded quantity across variants, base price, and the direction, Kinah difference and percentage change. Hover the change for the previous price. If no items qualify, the window explains this and offers **All Items**. This is the Aion implementation's explicit discovery rule; the published BDO guide describes price-change browsing but does not specify its proprietary initial ranking.

## Quantity defaults

**Transfer Item** and **Register Sale** include a framed **Always Max** checkbox beside **Max**. The setting is saved per account and defaults to off. When enabled, future transfer and sale dialogs start at their allowed maximum; the quantity can still be edited. Buy dialogs continue to start at one. Failed preference saves restore the confirmed setting and allow retry without closing the dialog. Preferences do not move items or change balances.

The flow follows Pearl Abyss's [Central Market guide](https://www.naeu.playblackdesert.com/en-US/Wiki?wikiNo=47): immediate stock purchases and sales into preorders, funded waiting buy orders, higher-bid priority, random matching among equal highest bids, and delayed high-value registration. Per-order item and Kinah collection is this server's requested behavior.

Simulation defaults to 3,000 traders and is separate from Broker. Account 0 represents virtual quotes, never a character or funded wallet. Only plain item variants are generated on funded purchases; player-sold gear retains its attributes. No simulated-to-simulated trades occur. Existing stock matches before on-demand quote rotation.

Maintenance refreshes at most 40 due quotes per pass, committing and releasing a fair matching lock for each variant so waiting clicks run before the worker acquires it again. Quotes are updated in place instead of producing cancelled rows on every rotation. Only variants with real open orders are matched. Full warehouse reads use a consistent database snapshot without the global matching lock; catalog and item reads are independent. Automatic polling refreshes account activity every 20 seconds without rebuilding warehouse or catalog contents, then refreshes the selected order book. Storage tabs switch cached panes locally without waiting for a server request. Native icon loading is deferred in small batches, and unchanged catalog, detail and activity nodes are retained. Fonts grow on large viewports and buttons use raised, shaded surfaces compatible with the embedded browser. These frontend changes do not establish a fix for the intermittent webpage-content flash shared by embedded windows.

### Transfer several items

1. Choose the source tab: Inventory, Character, Account or Market.
2. Click **Select Items**, then click the item stacks to mark them. **Ctrl + click** also enters selection mode. **Select All** marks unreserved items matching the current search; **Clear** removes the selection.
3. Click **Transfer Selected**, choose the destination, and click **Transfer All**. You can also drag a marked item onto the destination tab to open the same confirmation.
4. For Inventory, Character or Account selections, **Transfer Market Warehouse** appears when every selected stack is eligible, unreserved and fits the combined remaining market volume. It opens the confirmation with Market Warehouse selected. The button hides when any selected item becomes ineligible, the batch would exceed capacity, or the selection is cleared. Ordinary single-item Transfer can still deposit a smaller quantity that fits.

Up to 300 full stacks transfer in one transaction. Compatible stacks merge within their stack limits. If any item is restricted, reserved for sale, changed, or would exceed the destination capacity or Market Warehouse volume, the entire transfer is refused. No selected items move. Switching source tabs clears the selection. Single-item Transfer still supports a chosen quantity.

Quest items and items in dedicated special inventories are excluded from these storage lists and cannot be transferred through Central Market.

## Database

The following new InnoDB tables are created in the configured GameServer database:

| Table | Contents |
| --- | --- |
| `central_market_wallet` | Account Kinah, uncollected proceeds and wallet version |
| `central_market_catalog` | Item variants, base prices, absolute limits, traded quantities and recorded attributes |
| `central_market_orders` | Buy orders and sale listings, remaining quantities and registration times |
| `central_market_stock` | Account custody of items and sale reservations |
| `central_market_trades` | Completed trades and buyer/seller records |
| `central_market_collections` | Gross proceeds, tax and collected Kinah |
| `central_market_requests` | Committed request receipts preventing duplicate retries |
| `central_market_favorites` | Account Favorites |
| `central_market_searches` | The account's ten most recent saved searches |
| `central_market_simulation` | Per-variant quote refresh deadlines |
| `central_market_settlements` | Per-order outstanding proceeds and collected purchase quantities |
| `central_market_preferences` | Account-wide Always Max quantity preference |

Player-sold market items retain their original `inventory` row at **location 125**, owned by account ID. Existing `item_stones` rows retain manastones, Godstones, armsfusion stones and Idian. Plain simulated purchases create a fresh item transactionally; a custody reservation can reference either a sell order or a pending buy collection. Character deletion excludes account storage and market custody.

Escrow, fills, custody changes, refunds and ledger entries commit in one database transaction. Account ownership is checked server-side; an account cannot trade with itself. Native packets and embedded warehouse actions share the character connection guard. If a committed transfer cannot refresh the client, the server restores committed storage and disconnects it. A failed recovery blocks that character's inventory saves until a fresh login load.

Schema: [schema.sql](../game-server/config/central-market/schema.sql). Startup also verifies the required tables use InnoDB. The optional schema installer in `game-server/tools` reads a deployed GameServer's database settings; it does not copy or change character rows. See the repository README for deployment steps.

## BDO rules and Aion adaptations

The implementation uses published Central Market behavior, with Aion items and Kinah. It is not Pearl Abyss's proprietary server code.

| Rule | Implementation |
| --- | --- |
| Account storage | Shared across characters; 5,000 VT |
| Item volume | Weapons/armor 10 VT; accessories 5 VT; materials 0.1 VT; other items 0.3 VT |
| Capacity | Deposits cannot exceed capacity; purchased items can exceed it and remain withdrawable |
| Orders | Kinah or items reserved before matching; partial fills and cancellation refunds |
| Buy priority | Higher price first; random selection among equal highest eligible bids |
| Price limit ties | Equal highest buyers use the same lottery at every price; random eligible sale at the absolute floor |
| High-value listing | At least 20 billion Kinah: 15-minute registration queue, announcement and randomized equal-price buyers on release |
| Queued-price restriction | New bids cannot exceed the lowest queued listing's price |
| Duplicate registration | One active buy order per account/variant; one queued sale per account/item/enchantment/tempering |
| Collection | 65% of gross proceeds; Premium membership maps to the Value Pack's 84.5% return |
| Current price band | Up to ±7.5% around base, clamped to absolute limits |
| Item attributes | Aion enchantment, tempering, appearance, sockets, armsfusion and random bonuses remain with the actual item |


## Simulation and upgrades

`game-server/config/main/central-market-simulation.properties` enables simulation by default with population **3,000**, staggered 120–480 second refresh deadlines, and at most 100 units per simulated stack. Population controls activity, not online-character count. The default catalog batch warms the full catalog over the first few minutes. A clicked plain variant can initialize on demand. Simulated asks and bids need not cross immediately: choose stocked sale prices to buy now, or a waiting buyer's price to sell now. Modified player gear uses its own exact variant and is never generated by simulation.

Copy this properties file with the schema and browser assets when upgrading. Startup creates missing InnoDB tables, including `central_market_preferences`, and migrates old settlements: already delivered purchases stay delivered; only still-uncollected legacy proceeds become claimable. Keep all **12** market tables, inventory and item_stones in a consistent backup. Collection, cancel, and request retries cannot claim the same filled quantity twice.

For an existing deployment that already contains this repository's base Central Market, an optional focused update builder preserves other JAR entries:

```powershell
python game-server/tools/build_central_market_simulation.py --server 'C:\GameServer' --output 'C:\Market-Server-Staged'
```

This stages six hash-manifest files using that deployment's library classpath. It does not stop, replace, or restart a running server. Review the prepared manifest, stop GameServer gracefully, then run its generated `Install.ps1 -GameServerRoot 'C:\GameServer'`. The installer refuses a listening game port, validates before/after hashes, backs up replacements, and rolls back on failure. Use a full Maven build for a new installation or a different emulator revision.

## Client entry point and validation

The standalone client builder creates the Market HUD shortcut beside the stock Shop button, plus the existing fullscreen authenticated browser and native item-icon bridge. It patches both HUD layouts and the loaded English locale. `--menu-only` keeps the earlier Additional Functions entry instead. Existing installations with the HUD patch use the icon; allow the client to finish its login load before opening it. See the [repository README](../README.md) for installation and the [validation record](CENTRAL_MARKET_VALIDATION.md) for tested behavior and remaining in-game checks.

## Re-entering after a GameServer restart

Every world-entry packet now sends the authenticated account's current native web-session token. A fresh server account creates a token; repeat entries retain it. This restores the client's Central Market session when reconnecting from server selection without visiting the login screen. HTTP requests still require the current token and online player; no username, IP address or expired-token fallback is accepted. Verify this reconnect flow in the recipient's running client after deployment.

For release history through this update, see [CHANGELOG.md](../CHANGELOG.md).
