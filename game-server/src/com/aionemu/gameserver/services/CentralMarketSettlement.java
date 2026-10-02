package com.aionemu.gameserver.services;

import java.sql.*;
import static com.aionemu.gameserver.services.CentralMarketService.*;

/** Per-order claims, sharing the matching transaction and custody rows. */
final class CentralMarketSettlement {
	private CentralMarketSettlement() {}

	static void migrate(Connection c) throws SQLException {
		// Previously bought items were already delivered. Never make them claimable again.
		update(c,"INSERT IGNORE INTO central_market_settlements(order_id,collected_quantity) SELECT id,quantity-remaining FROM central_market_orders WHERE account_id>0 AND side='B'");
		for(var wallet:rows(c,"SELECT account_id,proceeds FROM central_market_wallet")) {
			int account=(int)lng(wallet,"account_id");
			long assigned=lng(row(c,"SELECT COALESCE(SUM(z.gross),0) n FROM central_market_settlements z JOIN central_market_orders o ON o.id=z.order_id WHERE o.account_id=? AND o.side='S'",account),"n");
			long remaining=Math.max(0,lng(wallet,"proceeds")-assigned);
			for(var order:rows(c,"SELECT o.id,COALESCE(SUM(t.quantity*t.unit_price),0) gross FROM central_market_orders o LEFT JOIN central_market_settlements z ON z.order_id=o.id LEFT JOIN central_market_trades t ON t.sell_order=o.id WHERE o.account_id=? AND o.side='S' AND z.order_id IS NULL GROUP BY o.id ORDER BY o.id DESC",account)) {
				long gross=Math.min(remaining,lng(order,"gross"));remaining-=gross;
				update(c,"INSERT INTO central_market_settlements(order_id,gross) VALUES(?,?)",lng(order,"id"),gross);
			}
			if(remaining!=0) throw new SQLException("Unassigned legacy market proceeds for account "+account);
		}
	}

	static String collect(Connection c,int account,long id,boolean premium) throws SQLException {
		var order=row(c,"SELECT * FROM central_market_orders WHERE id=? AND account_id=? FOR UPDATE",id,account);
		if(order==null) throw new IllegalArgumentException("Select one of your orders.");
		var claim=row(c,"SELECT * FROM central_market_settlements WHERE order_id=? FOR UPDATE",id);
		if(str(order,"side").equals("B")) {
			long quantity=claim==null?0:lng(order,"quantity")-lng(order,"remaining")-lng(claim,"collected_quantity");
			long held=lng(row(c,"SELECT COALESCE(SUM(i.item_count),0) n FROM central_market_stock s JOIN inventory i USING(item_unique_id) WHERE s.order_id=? AND s.account_id=?",id,account),"n");
			if(quantity<=0) throw new IllegalArgumentException("No purchased items to collect from this order.");
			if(held!=quantity) throw new SQLException("Purchase collection custody mismatch");
			update(c,"UPDATE central_market_stock SET order_id=NULL WHERE order_id=? AND account_id=?",id,account);
			update(c,"UPDATE central_market_settlements SET collected_quantity=collected_quantity+? WHERE order_id=?",quantity,id);
			return "Collected "+quantity+" items into Market Warehouse. Transfer them to Inventory when ready.";
		}
		long gross=claim==null?0:lng(claim,"gross");
		if(gross<=0) throw new IllegalArgumentException("No proceeds to collect from this order.");
		var wallet=row(c,"SELECT kinah,proceeds FROM central_market_wallet WHERE account_id=? FOR UPDATE",account);
		if(wallet==null || lng(wallet,"proceeds")<gross) throw new SQLException("Sale collection wallet mismatch");
		long net=CentralMarketRules.collect(gross,premium);
		update(c,"UPDATE central_market_wallet SET kinah=?,proceeds=proceeds-?,version=version+1 WHERE account_id=?",CentralMarketRules.add(lng(wallet,"kinah"),net),gross,account);
		update(c,"UPDATE central_market_settlements SET gross=0 WHERE order_id=?",id);
		update(c,"INSERT INTO central_market_collections(account_id,gross,net,collected_at) VALUES(?,?,?,?)",account,gross,net,System.currentTimeMillis());
		return "Collected "+net+" Kinah after market tax into Market Warehouse.";
	}
}
