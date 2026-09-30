# Central Market

The Central Market menu entry opens one Aion window containing Inventory, Character Warehouse, Account Warehouse, Market Warehouse and Central Market. The existing Broker remains a separate economy.

## Using the window

1. Open **Central Market** from Additional Functions.
2. Choose Inventory, Character or Account. Select an item and use **Transfer** or **Market Warehouse**. Double-click transfers an eligible item to Market Warehouse. Items can also be dragged onto a warehouse tab. Confirm the quantity and destination.
3. Deposit Kinah from the selected Inventory or warehouse. Buy orders use the Market Warehouse balance.
4. Search the market, choose an item and select its enchantment or tempering. Select a price in the order book, then **Buy / Place Order**. Available matching listings fill immediately; the remainder becomes a buy order.
5. To sell, select an unreserved item in Market Warehouse, then **Register Sale**. Listed items remain reserved until sold or cancelled.
6. **My Orders** shows active, queued, filled and cancelled orders. Cancel returns only the unfilled items or reserved Kinah.
7. **Collect** applies market tax and credits Market Warehouse Kinah. **Withdraw** transfers Kinah to the selected Inventory or warehouse.
8. Purchased items enter Market Warehouse. Transfer them to Inventory or a compatible warehouse.

Searches can be saved, items can be added to Favorites, and lists can be filtered, sorted and paged. Trade History includes purchases, sales and collections. Notifications show queued high-value listings. Item Details includes Aion stats and modified attributes; Item Preview opens the game's native preview window.

### Transfer several items

1. Choose the source tab: Inventory, Character, Account or Market.
2. Click **Select Items**, then click the item stacks to mark them. **Ctrl + click** also enters selection mode. **Select All** marks unreserved items matching the current search; **Clear** removes the selection.
3. Click **Transfer Selected**, choose the destination, and click **Transfer All**. You can also drag a marked item onto the destination tab to open the same confirmation.

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

Market items retain their original `inventory` row at **location 125**, owned by account ID. Existing `item_stones` rows retain manastones, Godstones, armsfusion stones and Idian. Items are not reconstructed from template IDs. Character deletion excludes account storage and market custody.

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
| Buy priority | Higher price first; ordinary equal-price orders by registration order |
| Price limit ties | Random eligible buyer at the absolute ceiling; random eligible sale at the absolute floor |
| High-value listing | At least 20 billion Kinah: 15-minute registration queue, announcement and randomized equal-price buyers on release |
| Queued-price restriction | New bids cannot exceed the lowest queued listing's price |
| Duplicate registration | One active buy order per account/variant; one queued sale per account/item/enchantment/tempering |
| Collection | 65% of gross proceeds; Premium membership maps to the Value Pack's 84.5% return |
| Current price band | Up to ±7.5% around base, clamped to absolute limits |
| Item attributes | Aion enchantment, tempering, appearance, sockets, armsfusion and random bonuses remain with the actual item |

BDO's complete price algorithm and all item-specific limits are not publicly specified. The Aion pricing policy is explicit in `CentralMarketRules` and `CentralMarketService`: initial prices use template value, level, quality and enhancement; absolute limits start at one tenth and ten times that seed; the ladder uses 0.5% ticks; open-order imbalance changes the base by 1% every eight hours. Order quantity is at most 1,000 or the template stack limit, and nonstackable gear uses quantity one. Existing orders remain visible when the price band moves.

The floor-price sale lottery is an Aion policy; the cited official sources explicitly document buyer lotteries. The implementation does not create an NPC market maker or duplicate BDO's older guide behavior where the Marketplace Director purchases initial stock.

BDO systems without an Aion equivalent—Family Fame, maids, Pearl items and its mobile application—are not introduced. Premium uses this server's existing membership value. Aion character/account warehouse restrictions remain enforced. Modified gear has separate exact variants rather than losing its Aion attributes.

Primary research:

- [Pearl Abyss Central Market guide](https://blackdesert.pearlabyss.com/Asia/en-US/Game/Wiki?_masterWikiNo=39)
- [Updated item volumes, March 2023](https://blackdesert.pearlabyss.com/Console/en-us/News/Notice/Detail?_boardNo=10996)
- [High-value registration and matching, June 2021](https://www.console.playblackdesert.com/News/Notice/Detail?boardNo=7580&countryType=en-US)
- [Registration-queue price restriction, January 2023](https://blackdesert.pearlabyss.com/Console/en-us/News/Notice/Detail?_boardNo=10873)
- [Central Market price limits, 2026](https://blackdesert.pearlabyss.com/Console/en-US/News/Notice/Detail?_boardNo=13304)
