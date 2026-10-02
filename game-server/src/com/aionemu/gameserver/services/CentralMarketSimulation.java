package com.aionemu.gameserver.services;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import com.aionemu.gameserver.configs.main.CentralMarketSimulationConfig;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import static com.aionemu.gameserver.services.CentralMarketService.*;

/** Persistent virtual quotes; real items are generated only when a funded player buys. */
final class CentralMarketSimulation {
	static final int ACCOUNT = 0;
	private CentralMarketSimulation() {}

	static long quantity(ItemTemplate t, String category) {
		if (!t.isStackable()) return 1;
		long cap = Math.max(1, Math.min(1000, Math.min(t.getMaxStackCount(), CentralMarketSimulationConfig.MAX_STACK)));
		long activity = Math.max(1, CentralMarketSimulationConfig.POPULATION / 30);
		if (!Set.of("Materials", "Consumables", "Enhancement").contains(category)) activity = Math.max(1, activity / 10);
		long max = Math.min(cap, activity);
		return ThreadLocalRandom.current().nextLong(Math.max(1, max / 3), max + 1);
	}

	static void refresh(Connection c, Map<String,Object> entry, long now) throws SQLException {
		if (!CentralMarketSimulationConfig.ENABLED || CentralMarketSimulationConfig.POPULATION <= 0) return;
		String key = str(entry,"variant");
		Map<String,Object> due = row(c,"SELECT next_refresh FROM central_market_simulation WHERE variant=?",key);
		if (due != null && lng(due,"next_refresh") > now) return;
		ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate((int)lng(entry,"item_id"));
		if (t == null || !eligible(t)) return;
		List<Long> levels = CentralMarketRules.ladder(lng(entry,"base_price"),lng(entry,"floor_price"),lng(entry,"ceiling_price"));
		var random = ThreadLocalRandom.current();
		// A spread prevents a guaranteed buy/resell profit. Occasional stronger bids create demand
		// at the upper band; lower bids and scarce supply leave some player orders waiting.
		int bidIndex = random.nextInt(levels.size());
		int askIndex = Math.min(levels.size()-1, bidIndex + 1 + random.nextInt(4));
		long qty = quantity(t,category(t));
		quote(c,key,"B",levels.get(bidIndex),qty,now);
		// Modified gear can be bought from players, but its skins/sockets/rolls are never invented.
		if (key.equals(t.getTemplateId()+":0:0")) quote(c,key,"S",levels.get(askIndex),qty,now);
		long min = Math.max(5,CentralMarketSimulationConfig.REFRESH_MIN_SECONDS);
		long max = Math.max(min,Math.min(86400,CentralMarketSimulationConfig.REFRESH_MAX_SECONDS));
		min = Math.min(min,max);
		long delay = random.nextLong(min,max+1) * 1000L * 3000 / Math.max(300,Math.min(30000,CentralMarketSimulationConfig.POPULATION));
		update(c,"INSERT INTO central_market_simulation VALUES(?,?) ON DUPLICATE KEY UPDATE next_refresh=VALUES(next_refresh)",key,now+Math.max(5000,delay));
	}

	private static void quote(Connection c,String key,String side,long price,long qty,long now) throws SQLException {
		// Virtual stock represents supply that has already completed registration.
		long available = now;
		if(update(c,"UPDATE central_market_orders SET price=?,quantity=?,remaining=?,created_at=?,available_at=? WHERE account_id=0 AND variant=? AND side=? AND state='OPEN'",price,qty,qty,now,available,key,side)>0) return;
		update(c,"INSERT INTO central_market_orders(account_id,variant,side,price,quantity,remaining,state,created_at,available_at) VALUES(0,?,?,?,?,?,?,?,?)",
			key,side,price,qty,qty,available>now?"QUEUED":"OPEN",now,available);
	}

	static void disable(Connection c) throws SQLException {
		update(c,"UPDATE central_market_orders SET state='CANCELLED' WHERE account_id=0 AND state IN ('OPEN','QUEUED')");
		update(c,"DELETE FROM central_market_simulation");
	}
}
