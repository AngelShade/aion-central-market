package com.aionemu.gameserver.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.configs.main.GSConfig;
import com.aionemu.gameserver.configs.main.CentralMarketSimulationConfig;
import com.aionemu.gameserver.dao.InventoryDAO;
import com.aionemu.gameserver.dao.ItemStoneListDAO;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.storage.*;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;
import com.aionemu.gameserver.restrictions.PlayerRestrictions;
import com.aionemu.gameserver.services.item.*;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemDeleteType;
import com.aionemu.gameserver.services.item.ItemPacketService.ItemUpdateType;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.utils.idfactory.IDFactory;
import com.aionemu.gameserver.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Account-wide market custody, transactional escrow and persistent order matching. */
public final class CentralMarketService {
	private static final Logger log = LoggerFactory.getLogger(CentralMarketService.class);
	private static final int MARKET = StorageType.MARKET_WAREHOUSE.getId();
	private static final ReentrantLock LOCK = new ReentrantLock(true);
	private static List<ItemTemplate> templates = List.of();
	private static Map<Integer,String> catalogCategories = Map.of();
	private static volatile boolean ready;
	private static boolean simulationInitialized;
	private static long housekeepingAt;
	private static final String COPY_COLUMNS = "item_id,item_count,item_color,color_expires,item_creator,expire_time,activation_count,item_owner,is_equipped,is_soul_bound,slot,item_location,enchant,enchant_bonus,item_skin,fusioned_item,optional_socket,optional_fusion_socket,charge,tune_count,rnd_bonus,fusion_rnd_bonus,tempering,pack_count,is_amplified,buff_skill,rnd_plume_bonus,rank_limit_expire_time";

	private CentralMarketService() {}

	public static void start() throws Exception {
		CentralMarketHttpService.loadIcons();
		try (Connection c = DatabaseFactory.getConnection(); Statement s = c.createStatement()) {
			String sql = Files.readString(Path.of("config/central-market/schema.sql"));
			for (String statement : sql.split(";")) if (!statement.isBlank()) s.execute(statement);
			for(var engine:rows(c,"SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND (TABLE_NAME IN ('inventory','item_stones') OR TABLE_NAME LIKE 'central_market_%')"))
				if(!"InnoDB".equalsIgnoreCase(str(engine,"ENGINE"))) throw new SQLException("Central Market requires InnoDB: "+str(engine,"TABLE_NAME"));
		}
		templates = DataManager.ITEM_DATA.getItemTemplates().stream().filter(CentralMarketService::eligible)
			.sorted(Comparator.comparing(ItemTemplate::getName)).toList();
		catalogCategories = templates.stream().collect(Collectors.toUnmodifiableMap(ItemTemplate::getTemplateId,CentralMarketService::category));
		try (Connection c = DatabaseFactory.getConnection()) {
			c.setAutoCommit(false);
			for (ItemTemplate t : templates) catalog(c, t, t.getTemplateId() + ":0:0", 0, 0);
			if(!CentralMarketSimulationConfig.ENABLED || CentralMarketSimulationConfig.POPULATION<=0) CentralMarketSimulation.disable(c);
			CentralMarketSettlement.migrate(c);
			c.commit();
		}
		ready = true;
		long tick=Math.max(200,Math.min(5000,Math.max(1,CentralMarketSimulationConfig.TICK_SECONDS)*1000L/Math.max(1,(CentralMarketSimulationConfig.CATALOG_BATCH+39)/40)));
		ThreadPoolManager.getInstance().scheduleAtFixedRate(CentralMarketService::maintain, 1000, tick);
		log.info("Central Market ready: {} tradeable templates, database custody enabled", templates.size());
	}

	public static boolean eligible(ItemTemplate t) {
		if (!visibleInStorage(t) || !CentralMarketHttpService.hasClientItem(t.getTemplateId()) || !t.isTradeable() || t.isKinah() || t.getExtraInventoryId() >= 0 || t.getExpireTime() != 0 || t.getPrice() < 0
			|| ((t.isWeapon() || t.isArmor() || t.isStigma()) && t.getLevel() > GSConfig.PLAYER_MAX_LEVEL)) return false;
		String n = t.getName().toLowerCase(Locale.ROOT);
		return !n.isBlank() && !n.matches(".*(test|debug|dummy|prototype|placeholder|prop weapon|prop sword).*" ) && !n.matches(".*\\bprop\\s*(sword|dagger|bow|staff|shield|mace).*" ) && !n.endsWith(" form") && !n.contains("npc")
			&& !n.startsWith("[temp") && (!(t.isWeapon() || t.isArmor()) || t.getPrice() != 5);
	}

	static boolean visibleInStorage(ItemTemplate t) {
		return t.getItemGroup() != ItemGroup.QUEST && t.getExtraInventoryId() < 1;
	}

	static String category(ItemTemplate t) {
		if (t.isWeapon()) return "Weapons";
		String g = t.getItemGroup().name();
		if (g.matches(".*(RING|EARRING|NECKLACE|BELT|PLUME|WING).*")) return "Accessories";
		if (t.isArmor()) return "Armor";
		if (g.matches(".*(MANASTONE|ENCHANTMENT|TAMPERING).*")) return "Enhancement";
		if (g.matches(".*(FLUX|MATERIAL|GATHERABLE|ORE|WOOD).*")) return "Materials";
		if (g.matches(".*(DRINK|FOOD|SCROLL|POTION).*")) return "Consumables";
		if (t.isStigma()) return "Stigmas";
		return "Other";
	}

	static String variant(Item i) {
		String key = i.getItemId() + ":" + i.getEnchantLevel() + ":" + i.getTempering();
		// Exact variants prevent orders for plain gear receiving altered sockets, skins or rolls.
		String attributes = i.getItemSkinTemplate().getTemplateId() + ":" + i.getFusionedItemId() + ":" + i.getBonusStatsId()
			+ ":" + i.getFusionedItemBonusStatsId() + ":" + i.getGodStoneId() + ":" + (i.getGodStone()==null?0:i.getGodStone().getActivatedCount()) + ":" + i.getItemColor() + ":" + i.getPackCount()
			+ ":" + i.getEnchantBonus() + ":" + i.getOptionalSockets() + ":" + i.getFusionedItemOptionalSockets()
			+ ":" + i.getChargePoints() + ":" + i.getBuffSkill() + ":" + i.getRndPlumeBonusValue() + ":" + i.getTuneCount() + ":" + i.isAmplified()
			+ ":" + i.getItemStones().stream().map(s -> s.getSlot() + ":" + s.getItemId()).sorted().collect(Collectors.joining(","))
			+ ":" + i.getFusionStones().stream().map(s -> s.getSlot() + ":" + s.getItemId()).sorted().collect(Collectors.joining(","))
			+ ":" + (i.getIdianStone()==null ? "" : i.getIdianStone().getItemId()+":"+i.getIdianStone().getPolishNumber()+":"+i.getIdianStone().getPolishCharge());
		if (i.getItemSkinTemplate().getTemplateId() != i.getItemId() || i.getFusionedItemId() != 0 || i.getBonusStatsId() != 0
			|| i.getGodStoneId() != 0 || i.getItemColor() != null || i.getPackCount() != 0 || i.getEnchantBonus() != 0
			|| i.getOptionalSockets() != 0 || i.getChargePoints() != 0 || i.getBuffSkill() != 0 || i.getRndPlumeBonusValue() != 0 || i.hasManaStones()
			|| (i.getTuneCount()!=0 && i.getTuneCount()!=-1) || i.isAmplified()!= (i.getItemTemplate().getEnchantType()==1) || i.getIdianStone()!=null || !i.getFusionStones().isEmpty()) {
			try { key += ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(attributes.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
			catch (Exception e) { throw new IllegalStateException(e); }
		}
		return key;
	}

	private static void catalog(Connection c, ItemTemplate t, String key, int enchant, int tempering) throws SQLException {
		long quality = t.getItemQuality() == null ? 0 : t.getItemQuality().getQualityId();
		long level = Math.max(1, t.getLevel());
		long seed = Math.max(t.getPrice(), 100 + level * level * (2 + quality));
		if (t.getItemGroup() == ItemGroup.MANASTONE || t.getItemGroup() == ItemGroup.SPECIAL_MANASTONE) seed = Math.max(seed, 500 + level * level * 8);
		if (t.getItemGroup() == ItemGroup.ENCHANTMENT) seed = Math.max(seed, 2000 + level * level * 30);
		seed = Math.min(1_000_000_000_000L, seed * (1L + enchant * enchant + tempering * tempering * 2L));
		update(c, "INSERT IGNORE INTO central_market_catalog(variant,item_id,enchant,tempering,base_price,floor_price,ceiling_price,previous_price) VALUES(?,?,?,?,?,?,?,?)",
			key, t.getTemplateId(), enchant, tempering, seed, Math.max(1, seed / 10), Math.min(CentralMarketRules.MAX_KINAH, seed * 10), seed);
	}

	public static String action(Player p, Map<String,String> a, String requestId) throws Exception {
		if (!ready) throw new IllegalStateException("Central Market is unavailable.");
		String preference=CentralMarketPreferences.tryAction(p,a,requestId);if(preference!=null)return preference;
		Object guard = p.getClientConnection();
		if (guard == null) throw new IllegalArgumentException("Log in to use Central Market.");
		synchronized (guard) {
			LOCK.lock();try {
				if (World.getInstance().getPlayer(p.getObjectId()) != p || p.isDead() || p.isTrading() || GameServer.isShuttingDownSoon())
					throw new IllegalArgumentException("Warehouse is unavailable during trade, death or shutdown.");
				if(Set.of("transfer","transferBatch","kinah").contains(a.getOrDefault("action",""))) {
					if (!InventoryDAO.store(p)) throw new SQLException("Inventory save failed");
					ItemStoneListDAO.save(p);
				}
				int account = p.getAccount().getId();
				List<Runnable> committed = new ArrayList<>();
				List<Integer> allocated = new ArrayList<>();
				try (Connection c = DatabaseFactory.getConnection()) {
					c.setAutoCommit(false);
					boolean databaseCommitted = false;
					try {
						update(c, "INSERT IGNORE INTO central_market_wallet(account_id) VALUES(?)", account);
						row(c, "SELECT * FROM central_market_wallet WHERE account_id=? FOR UPDATE", account);
						Map<String,Object> prior = row(c, "SELECT result FROM central_market_requests WHERE request_id=? AND account_id=?", requestId, account);
						if (prior != null) return str(prior,"result");
						String result = switch (a.getOrDefault("action", "")) {
							case "transfer" -> transfer(c,p,a,committed,allocated);
							case "transferBatch" -> transferBatch(c,p,a,committed);
							case "kinah" -> kinah(c,p,a,committed,allocated);
							case "buy", "sell" -> order(c,p,a,allocated,committed);
							case "cancel" -> cancel(c, account, number(a,"order"));
							case "collect" -> CentralMarketSettlement.collect(c,account,number(a,"order"),p.getAccount().getMembership()>0);
							case "favorite" -> favorite(c,account,(int)number(a,"item"));
							case "saveSearch" -> saveSearch(c,account,a);
							default -> throw new IllegalArgumentException("Unknown market action.");
						};
						update(c,"INSERT INTO central_market_requests VALUES(?,?,?,?)",requestId,account,result,System.currentTimeMillis());
						c.commit();
						databaseCommitted = true;
						for (Runnable apply : committed) {
							try { apply.run(); }
							catch (Exception e) {
								log.error("Committed market transfer could not refresh {}. Restoring database contents before disconnect.",p,e);
								try { for (int location : new int[]{0,1,2}) {
									int owner=location==2?account:p.getObjectId();
									List<Item> saved=new ArrayList<>();
									for(Map<String,Object> r:rows(c,"SELECT item_unique_id FROM inventory WHERE item_owner=? AND item_location=? AND is_equipped=0",owner,location)) saved.add(InventoryDAO.loadTransactionItem(c,(int)lng(r,"item_unique_id")));
									loadStones(c,saved);p.getStorage(location).restoreCommittedItems(saved);
								} } catch (Exception recoveryFailure) {
									InventoryDAO.quarantineMarketInventory(p.getObjectId());
									log.error("Market recovery failed for {}. Inventory saves blocked until a fresh login.",p,recoveryFailure);
								} finally { p.getClientConnection().close(); }
								return result+" Reconnect to refresh Inventory.";
							}
						}
						return result;
					} catch (Exception e) {
						if (!databaseCommitted) { c.rollback(); for (int id : allocated) IDFactory.getInstance().releaseId(id); }
						throw e;
					}
				}
			} finally { LOCK.unlock(); }
		}
	}

	private static String transfer(Connection c, Player p, Map<String,String> a, List<Runnable> committed, List<Integer> allocated) throws Exception {
		int source = (int)number(a,"source"), target = (int)number(a,"target"), id = (int)number(a,"object");
		long qty = number(a,"quantity");
		if (!Set.of(0,1,2,MARKET).contains(source) || !Set.of(0,1,2,MARKET).contains(target) || source == target || qty <= 0)
			throw new IllegalArgumentException("Select an item, warehouse and quantity.");
		int account = p.getAccount().getId();
		Storage origin = source == MARKET ? null : p.getStorage(source);
		Storage destination = target == MARKET ? null : p.getStorage(target);
		Item item = source == MARKET ? marketItems(account).stream().filter(i -> i.getObjectId() == id).findFirst().orElse(null) : origin.getItemByObjId(id);
		if (item == null || item.isEquipped() || !visibleInStorage(item.getItemTemplate()) || item.getItemTemplate().isKinah() || qty > item.getItemCount())
			throw new IllegalArgumentException("Item or quantity changed. Refresh the warehouse.");
		if (source == MARKET && row(c,"SELECT item_unique_id FROM central_market_stock WHERE item_unique_id=? AND account_id=? AND order_id IS NULL FOR UPDATE",id,account) == null)
			throw new IllegalArgumentException("This item is reserved for a sale.");
		if (target == MARKET && (!eligible(item.getItemTemplate()) || !item.isTradeable() || item.getExpireTime() != 0 || item.getPendingTuneResult() != null))
			throw new IllegalArgumentException("Only tradeable, permanent items can enter Market Warehouse.");
		Item merge = destination == null || !item.getItemTemplate().isStackable() ? null : destination.getItemsByItemId(item.getItemId()).stream()
			.filter(i -> variant(i).equals(variant(item)) && i.isSoulBound()==item.isSoulBound() && i.getExpireTime()==item.getExpireTime()
				&& i.getActivationCount()==item.getActivationCount() && i.getItemCount() <= item.getItemTemplate().getMaxStackCount() - qty).findFirst().orElse(null);
		if (destination != null && (ItemRestrictionService.isItemRestrictedTo(p,item,destination.getStorageType()) || merge == null && destination.isFull()))
			throw new IllegalArgumentException("Warehouse is full or this item cannot be stored there.");
		if (origin != null && ItemRestrictionService.isItemRestrictedFrom(p,item,origin.getStorageType()))
			throw new IllegalArgumentException("This item cannot leave its warehouse.");
		if (target == MARKET && volume(c,account) + CentralMarketRules.volume(category(item.getItemTemplate())) * qty > CentralMarketRules.BASE_VOLUME)
			throw new IllegalArgumentException("Market Warehouse volume limit reached.");
		if (!item.getItemTemplate().isStackable() && qty != item.getItemCount()) throw new IllegalArgumentException("This item cannot be split.");
		boolean split = qty < item.getItemCount();
		int sourceOwner = source == 2 || source == MARKET ? account : p.getObjectId();
		Map<String,Object> persisted = row(c,"SELECT item_count FROM inventory WHERE item_unique_id=? AND item_owner=? AND item_location=? FOR UPDATE",id,sourceOwner,source);
		if (persisted == null || lng(persisted,"item_count") != item.getItemCount()) throw new IllegalArgumentException("Item changed. Refresh Warehouse.");
		if (origin != null) persistStones(c,item);
		int movedId = split ? split(c,id,qty,allocated) : id;
		int owner = target == 2 || target == MARKET ? account : p.getObjectId();
		update(c,"UPDATE inventory SET item_owner=?,item_location=?,slot=65535 WHERE item_unique_id=?",owner,target,movedId);
		if (source == MARKET && !split) update(c,"DELETE FROM central_market_stock WHERE item_unique_id=?",id);
		if (target == MARKET) {
			String key = variant(item);
			catalog(c,item.getItemTemplate(),key,item.getEnchantLevel(),item.getTempering());
			update(c,"UPDATE central_market_catalog SET attributes_json=? WHERE variant=?",com.alibaba.fastjson2.JSON.toJSONString(itemView(item,MARKET).get("attributes")),key);
			update(c,"INSERT INTO central_market_stock(item_unique_id,account_id,variant) VALUES(?,?,?)",movedId,account,key);
		}
		long remaining = item.getItemCount() - qty;
		Item prepared = split && destination != null ? InventoryDAO.loadTransactionItem(c,movedId) : item;
		long mergedCount = merge == null ? 0 : merge.getItemCount()+qty;
		if(merge!=null) {
			update(c,"UPDATE inventory SET item_count=? WHERE item_unique_id=?",mergedCount,merge.getObjectId());
			update(c,"DELETE FROM inventory WHERE item_unique_id=?",movedId);
		}
		committed.add(() -> {
			Item moved = prepared;
			if (origin != null) {
				if (split) {
					item.setItemCount(remaining); item.setPersistentState(PersistentState.UPDATED);
					ItemPacketService.sendItemUpdatePacket(p,origin.getStorageType(),item,ItemUpdateType.DEC_ITEM_USE);
				} else {
					origin.remove(item);
					ItemPacketService.sendItemDeletePacket(p,origin.getStorageType(),item,ItemDeleteType.MOVE);
				}
			}
			if (destination != null) {
				if(merge!=null) {
					merge.setItemCount(mergedCount); merge.setPersistentState(PersistentState.UPDATED);
					ItemPacketService.sendItemUpdatePacket(p,destination.getStorageType(),merge,ItemUpdateType.INC_ITEM_COLLECT);
					IDFactory.getInstance().releaseId(movedId);
				} else {
					moved.setItemCount(qty); moved.setEquipmentSlot(65535); moved.setItemLocation(target);
					if(destination.add(moved)==null) throw new IllegalStateException("Destination rejected committed item");
					moved.setPersistentState(PersistentState.UPDATED);
				}
			} else if (!split) { moved.setItemLocation(MARKET); moved.setPersistentState(PersistentState.UPDATED); }
		});
		return "Transferred " + qty + " " + item.getItemName() + ".";
	}

	static Map<Integer,Long> batchEntries(String text) {
		if (text == null || text.isBlank()) throw new IllegalArgumentException("Select items to transfer.");
		String[] entries = text.split(",", -1);
		if (entries.length > 300) throw new IllegalArgumentException("Select up to 300 item stacks.");
		Map<Integer,Long> selected = new LinkedHashMap<>();
		for (String entry : entries) {
			if (!entry.matches("[1-9][0-9]{0,9}:[1-9][0-9]{0,14}")) throw new IllegalArgumentException("Invalid item selection.");
			String[] fields = entry.split(":");
			try {
				if (selected.put(Integer.parseInt(fields[0]),Long.parseLong(fields[1])) != null)
					throw new IllegalArgumentException("An item was selected twice.");
			} catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid item selection."); }
		}
		return selected;
	}

	static boolean sameStack(Item a, Item b) {
		return a.getItemId() == b.getItemId() && a.getItemTemplate().isStackable()
			&& variant(a).equals(variant(b)) && a.isSoulBound() == b.isSoulBound()
			&& a.getExpireTime() == b.getExpireTime() && a.getActivationCount() == b.getActivationCount();
	}

	// Plan without changing live Item objects. Later stacks can merge into earlier additions.
	static record BatchPlan(Map<Item,Item> merges, Map<Item,Long> counts, List<Item> additions) {}
	static BatchPlan planBatch(List<Item> selected, List<Item> destination, int freeSlots, boolean market) {
		Map<Item,Item> merges = new LinkedHashMap<>();
		Map<Item,Long> counts = new LinkedHashMap<>();
		List<Item> candidates = new ArrayList<>(destination), additions = new ArrayList<>();
		for (Item item : selected) {
			Item merge = null;
			if (!market) for (Item candidate : candidates) {
				long count = counts.getOrDefault(candidate,candidate.getItemCount());
				if (sameStack(item,candidate) && count <= item.getItemTemplate().getMaxStackCount() - item.getItemCount()) {
					merge = candidate; counts.put(candidate,count + item.getItemCount()); break;
				}
			}
			if (merge != null) merges.put(item,merge);
			else {
				if (!market && additions.size() >= freeSlots) throw new IllegalArgumentException("Destination warehouse is full. No items were transferred.");
				additions.add(item); candidates.add(item); counts.put(item,item.getItemCount());
			}
		}
		return new BatchPlan(merges,counts,additions);
	}

	private static String transferBatch(Connection c, Player p, Map<String,String> a, List<Runnable> committed) throws Exception {
		int source = (int)number(a,"source"), target = (int)number(a,"target"), account = p.getAccount().getId();
		if (!Set.of(0,1,2,MARKET).contains(source) || !Set.of(0,1,2,MARKET).contains(target) || source == target)
			throw new IllegalArgumentException("Select a destination warehouse.");
		Map<Integer,Long> entries = batchEntries(a.get("items"));
		Storage origin = source == MARKET ? null : p.getStorage(source), destination = target == MARKET ? null : p.getStorage(target);
		Map<Integer,Item> available = (origin == null ? marketItems(account) : origin.getItems()).stream().collect(Collectors.toMap(Item::getObjectId,i->i));
		List<Item> selected = new ArrayList<>();
		int sourceOwner = source == 2 || source == MARKET ? account : p.getObjectId();
		long marketVolume = target == MARKET ? volume(c,account) : 0;
		for (var entry : entries.entrySet()) {
			Item item = available.get(entry.getKey());
			if (item == null || item.isEquipped() || item.getItemTemplate().isKinah() || !visibleInStorage(item.getItemTemplate()) || item.getItemCount() != entry.getValue())
				throw new IllegalArgumentException("Item selection changed. Refresh Warehouse. No items were transferred.");
			Map<String,Object> saved = row(c,"SELECT item_count FROM inventory WHERE item_unique_id=? AND item_owner=? AND item_location=? FOR UPDATE",item.getObjectId(),sourceOwner,source);
			if (saved == null || lng(saved,"item_count") != item.getItemCount()) throw new IllegalArgumentException("Item changed. No items were transferred.");
			if (source == MARKET && row(c,"SELECT item_unique_id FROM central_market_stock WHERE item_unique_id=? AND account_id=? AND order_id IS NULL FOR UPDATE",item.getObjectId(),account) == null)
				throw new IllegalArgumentException("A selected item is reserved for a sale. No items were transferred.");
			if (origin != null && ItemRestrictionService.isItemRestrictedFrom(p,item,origin.getStorageType())
				|| destination != null && ItemRestrictionService.isItemRestrictedTo(p,item,destination.getStorageType()))
				throw new IllegalArgumentException(item.getItemName() + " cannot be transferred to this warehouse. No items were transferred.");
			if (target == MARKET) {
				if (!eligible(item.getItemTemplate()) || !item.isTradeable() || item.getExpireTime() != 0 || item.getPendingTuneResult() != null)
					throw new IllegalArgumentException(item.getItemName() + " cannot enter Market Warehouse. No items were transferred.");
				long unitVolume = CentralMarketRules.volume(category(item.getItemTemplate()));
				if (item.getItemCount() > (CentralMarketRules.BASE_VOLUME - marketVolume) / unitVolume)
					throw new IllegalArgumentException("Market Warehouse volume limit reached. No items were transferred.");
				marketVolume += unitVolume * item.getItemCount();
			}
			selected.add(item);
		}
		List<Item> destinationItems = destination == null ? List.of() : destination.getItems();
		int occupied = (int)destinationItems.stream().filter(i->!i.isEquipped() && i.getItemTemplate().getExtraInventoryId()<1).count();
		BatchPlan plan = planBatch(selected,destinationItems,destination==null?0:destination.getLimit()-occupied,target==MARKET);
		// Lock and validate every existing merge target before making any custody changes.
		int owner = target == 2 || target == MARKET ? account : p.getObjectId();
		for (Item item : plan.counts().keySet()) if (!plan.additions().contains(item)) {
			Map<String,Object> saved = row(c,"SELECT item_count FROM inventory WHERE item_unique_id=? AND item_owner=? AND item_location=? FOR UPDATE",item.getObjectId(),owner,target);
			if (saved == null || lng(saved,"item_count") != item.getItemCount()) throw new IllegalArgumentException("Destination items changed. No items were transferred.");
		}
		persistBatch(c,selected,plan,source,target,account,owner);
		committed.add(() -> {
			for (Item item : selected) if (origin != null) {
				origin.remove(item); ItemPacketService.sendItemDeletePacket(p,origin.getStorageType(),item,ItemDeleteType.MOVE);
			}
			for (var entry : plan.counts().entrySet()) {
				Item item = entry.getKey(); item.setItemCount(entry.getValue());
				if (plan.additions().contains(item)) {
					item.setEquipmentSlot(65535); item.setItemLocation(target);
					if (destination != null && destination.add(item) == null) throw new IllegalStateException("Destination rejected committed batch item");
				} else ItemPacketService.sendItemUpdatePacket(p,destination.getStorageType(),item,ItemUpdateType.INC_ITEM_COLLECT);
				item.setPersistentState(PersistentState.UPDATED);
			}
			for (Item item : plan.merges().keySet()) IDFactory.getInstance().releaseId(item.getObjectId());
		});
		return "Transferred " + selected.size() + (selected.size() == 1 ? " item stack." : " item stacks.");
	}

	static void persistBatch(Connection c, List<Item> selected, BatchPlan plan, int source, int target, int account, int owner) throws SQLException {
		for (Item item : selected) {
			if (source != MARKET) persistStones(c,item);
			if (source == MARKET) update(c,"DELETE FROM central_market_stock WHERE item_unique_id=?",item.getObjectId());
			if (plan.merges().containsKey(item)) update(c,"DELETE FROM inventory WHERE item_unique_id=?",item.getObjectId());
			else {
				update(c,"UPDATE inventory SET item_owner=?,item_location=?,slot=65535 WHERE item_unique_id=?",owner,target,item.getObjectId());
				if (target == MARKET) {
					String key = variant(item); catalog(c,item.getItemTemplate(),key,item.getEnchantLevel(),item.getTempering());
					update(c,"UPDATE central_market_catalog SET attributes_json=? WHERE variant=?",com.alibaba.fastjson2.JSON.toJSONString(itemView(item,MARKET).get("attributes")),key);
					update(c,"INSERT INTO central_market_stock(item_unique_id,account_id,variant) VALUES(?,?,?)",item.getObjectId(),account,key);
				}
			}
		}
		for (var entry : plan.counts().entrySet()) update(c,"UPDATE inventory SET item_count=? WHERE item_unique_id=?",entry.getValue(),entry.getKey().getObjectId());
	}

	private static void persistStones(Connection c,Item item) throws SQLException {
		if(!item.getItemTemplate().isArmor() && !item.getItemTemplate().isWeapon()) return;
		update(c,"DELETE FROM item_stones WHERE item_unique_id=?",item.getObjectId());
		for(var stone:item.getItemStones()) update(c,ItemStoneListDAO.INSERT_QUERY,item.getObjectId(),stone.getItemId(),stone.getSlot(),0,0,0,0);
		for(var stone:item.getFusionStones()) update(c,ItemStoneListDAO.INSERT_QUERY,item.getObjectId(),stone.getItemId(),stone.getSlot(),2,0,0,0);
		if(item.getGodStone()!=null) { var stone=item.getGodStone();update(c,ItemStoneListDAO.INSERT_QUERY,item.getObjectId(),stone.getItemId(),stone.getSlot(),1,0,0,stone.getActivatedCount()); }
		if(item.getIdianStone()!=null) { var stone=item.getIdianStone();update(c,ItemStoneListDAO.INSERT_QUERY,item.getObjectId(),stone.getItemId(),stone.getSlot(),3,stone.getPolishNumber(),stone.getPolishCharge(),0); }
	}

	private static int split(Connection c, int source, long qty, List<Integer> allocated) throws SQLException {
		Map<String,Object> original = row(c,"SELECT item_count FROM inventory WHERE item_unique_id=? FOR UPDATE",source);
		if (original == null || qty <= 0 || qty >= lng(original,"item_count")) throw new IllegalArgumentException("Item quantity changed.");
		int id = IDFactory.getInstance().nextId(); allocated.add(id);
		update(c,"INSERT INTO inventory(item_unique_id,"+COPY_COLUMNS+") SELECT ?,"+COPY_COLUMNS+" FROM inventory WHERE item_unique_id=?",id,source);
		update(c,"INSERT INTO item_stones(item_unique_id,item_id,slot,category,polishNumber,polishCharge,proc_count) SELECT ?,item_id,slot,category,polishNumber,polishCharge,proc_count FROM item_stones WHERE item_unique_id=?",id,source);
		update(c,"UPDATE inventory SET item_count=? WHERE item_unique_id=?",qty,id);
		update(c,"UPDATE inventory SET item_count=item_count-? WHERE item_unique_id=?",qty,source);
		return id;
	}

	private static String kinah(Connection c, Player p, Map<String,String> a, List<Runnable> committed, List<Integer> allocated) throws SQLException {
		long amount = number(a,"quantity"); int storageId = (int)number(a,"source");
		if (amount <= 0 || amount > CentralMarketRules.MAX_KINAH || !Set.of(0,1,2).contains(storageId)) throw new IllegalArgumentException("Enter a valid Kinah amount.");
		Storage storage = p.getStorage(storageId); int account = p.getAccount().getId();
		boolean deposit = "deposit".equals(a.get("direction"));
		if (!deposit && !"withdraw".equals(a.get("direction"))) throw new IllegalArgumentException("Select deposit or withdraw.");
		long wallet = lng(row(c,"SELECT kinah FROM central_market_wallet WHERE account_id=?",account),"kinah");
		if (deposit && storage.getKinah() < amount || !deposit && wallet < amount) throw new IllegalArgumentException("Not enough Kinah.");
		long newStorage = deposit ? storage.getKinah() - amount : CentralMarketRules.add(storage.getKinah(),amount);
		long newWallet = deposit ? CentralMarketRules.add(wallet,amount) : wallet - amount;
		Item originalCoin = storage.getKinahItem();
		Item coin = originalCoin == null ? ItemFactory.newItem(com.aionemu.gameserver.model.items.ItemId.KINAH,newStorage) : originalCoin;
		if (originalCoin == null) {
			allocated.add(coin.getObjectId()); coin.setItemLocation(storageId);
			if (!InventoryDAO.insertTransactionItem(c,coin,p)) throw new SQLException("Kinah row could not be created");
		} else update(c,"UPDATE inventory SET item_count=? WHERE item_unique_id=?",newStorage,coin.getObjectId());
		update(c,"UPDATE central_market_wallet SET kinah=?,version=version+1 WHERE account_id=?",newWallet,account);
		committed.add(() -> { if(originalCoin==null) storage.add(coin); coin.setItemCount(newStorage); coin.setPersistentState(PersistentState.UPDATED); ItemPacketService.sendItemUpdatePacket(p,storage.getStorageType(),coin,deposit ? ItemUpdateType.DEC_KINAH_BUY : ItemUpdateType.INC_KINAH_COLLECT); });
		return (deposit ? "Deposited " : "Withdrew ") + amount + " Kinah.";
	}

	private static String order(Connection c, Player p, Map<String,String> a, List<Integer> allocated, List<Runnable> committed) throws Exception {
		if (!PlayerRestrictions.canTrade(p)) throw new IllegalArgumentException("Trading is unavailable.");
		int account = p.getAccount().getId(); String side = a.get("action").equals("buy") ? "B" : "S";
		long qty = number(a,"quantity"), price = number(a,"price");
		String key = a.getOrDefault("variant",""); Item stock = null;
		if (side.equals("S")) {
			int id = (int)number(a,"object");
			stock = marketItems(account).stream().filter(i -> i.getObjectId() == id).findFirst().orElse(null);
			if (stock == null || row(c,"SELECT item_unique_id FROM central_market_stock WHERE item_unique_id=? AND account_id=? AND order_id IS NULL FOR UPDATE",id,account) == null)
				throw new IllegalArgumentException("Select an available Market Warehouse item.");
			key = variant(stock);
			if (qty > stock.getItemCount()) throw new IllegalArgumentException("Not enough items in Market Warehouse.");
			if (!stock.isTradeable()) throw new IllegalArgumentException("This item cannot be traded.");
		}
		Map<String,Object> entry = row(c,"SELECT * FROM central_market_catalog WHERE variant=? FOR UPDATE",key);
		if (entry == null) throw new IllegalArgumentException("Select a market item.");
		ItemTemplate template = DataManager.ITEM_DATA.getItemTemplate((int)lng(entry,"item_id"));
		if(template==null || !eligible(template)) throw new IllegalArgumentException("This item cannot be traded.");
		long maxQty = template.isStackable() ? Math.min(template.getMaxStackCount(), 1000) : 1;
		if (qty <= 0 || qty > maxQty) throw new IllegalArgumentException("Order quantity must be between 1 and " + maxQty + ".");
		List<Long> ladder = CentralMarketRules.ladder(lng(entry,"base_price"),lng(entry,"floor_price"),lng(entry,"ceiling_price"));
		if (!ladder.contains(price)) throw new IllegalArgumentException("Market prices changed. Select a price from the current order book.");
		long cost = CentralMarketRules.total(price,qty);
		if (lng(row(c,"SELECT COUNT(*) n FROM central_market_orders WHERE account_id=? AND state IN ('OPEN','QUEUED')",account),"n") >= CentralMarketRules.MAX_ORDERS)
			throw new IllegalArgumentException("Cancel an order before registering another.");
		long now = System.currentTimeMillis(), available = side.equals("S") && price >= CentralMarketRules.HIGH_VALUE ? now + CentralMarketRules.REGISTRATION_DELAY : now;
		if (side.equals("B") && row(c,"SELECT id FROM central_market_orders WHERE account_id=? AND variant=? AND side='B' AND state='OPEN'",account,key)!=null)
			throw new IllegalArgumentException("A buy order for this item is already active. Cancel it before placing another.");
		if (available>now && row(c,"SELECT o.id FROM central_market_orders o JOIN central_market_catalog t ON t.variant=o.variant WHERE o.account_id=? AND t.item_id=? AND t.enchant=? AND t.tempering=? AND o.side='S' AND o.state='QUEUED'",account,template.getTemplateId(),lng(entry,"enchant"),lng(entry,"tempering"))!=null)
			throw new IllegalArgumentException("A sale for this item is already in the registration queue.");
		if (side.equals("B")) {
			Map<String,Object> queue = row(c,"SELECT MIN(price) price FROM central_market_orders WHERE variant=? AND state='QUEUED'",key);
			if (queue != null && queue.get("price") != null && price > lng(queue,"price")) throw new IllegalArgumentException("A sale is in the registration queue. Select its price or lower.");
			if (update(c,"UPDATE central_market_wallet SET kinah=kinah-?,version=version+1 WHERE account_id=? AND kinah>=?",cost,account,cost) != 1)
				throw new IllegalArgumentException("Not enough Kinah in Market Warehouse.");
		}
		long order;
		try (PreparedStatement s = c.prepareStatement("INSERT INTO central_market_orders(account_id,variant,side,price,quantity,remaining,state,created_at,available_at) VALUES(?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
			bind(s,account,key,side,price,qty,qty,available > now ? "QUEUED" : "OPEN",now,available); s.executeUpdate();
			try (ResultSet r=s.getGeneratedKeys()) { r.next(); order=r.getLong(1); }
		}
		if (stock != null) {
			int id = stock.getObjectId();
			if (qty < stock.getItemCount()) {
				id = split(c,id,qty,allocated);
				update(c,"INSERT INTO central_market_stock(item_unique_id,account_id,variant,order_id) VALUES(?,?,?,?)",id,account,key,order);
			} else update(c,"UPDATE central_market_stock SET order_id=? WHERE item_unique_id=?",order,id);
		}
		if (available == now) {
			// Fill resting stock before replacing any simulation quote the player just saw.
			match(c,key,allocated);
			CentralMarketSimulation.refresh(c,entry,now);
			match(c,key,allocated);
		}
		else committed.add(()-> { for(Player online:World.getInstance().getAllPlayers()) com.aionemu.gameserver.utils.PacketSendUtility.sendMessage(online,"Central Market: "+template.getName()+" listed for "+price+" Kinah. Available in 15 minutes."); });
		long remaining=lng(row(c,"SELECT remaining FROM central_market_orders WHERE id=?",order),"remaining");
		long filled=qty-remaining;
		return (side.equals("B") ? "Bought " : "Sold ")+filled+" / "+qty+" items. "
			+ (filled>0 ? "Collect "+(side.equals("B")?"items":"Kinah")+" beside this order in My Orders. " : "")
			+ (available>now ? "Sale queued for 15 minutes." : remaining>0 ? remaining+" items remain on order." : "Order complete.");
	}

	private static void match(Connection c, String key, List<Integer> allocated) throws SQLException {
		Map<String,Object> entry = row(c,"SELECT item_id,floor_price,ceiling_price FROM central_market_catalog WHERE variant=?",key);
		while (true) {
			List<Map<String,Object>> sells = rows(c,"SELECT * FROM central_market_orders WHERE variant=? AND side='S' AND state='OPEN' AND remaining>0 ORDER BY price,created_at,id FOR UPDATE",key);
			List<Map<String,Object>> buys = rows(c,"SELECT * FROM central_market_orders WHERE variant=? AND side='B' AND state='OPEN' AND remaining>0 ORDER BY price DESC,created_at,id FOR UPDATE",key);
			if(!sells.isEmpty() && lng(sells.getFirst(),"price")==lng(entry,"floor_price")) {
				int end=0;while(end<sells.size() && lng(sells.get(end),"price")==lng(entry,"floor_price")) end++;
				Collections.shuffle(sells.subList(0,end));
			}
			Map<String,Object> sell = null, buy = null;
			for (Map<String,Object> s : sells) {
				List<Map<String,Object>> candidates = buys.stream().filter(b -> lng(b,"price") >= lng(s,"price") && lng(b,"account_id") != lng(s,"account_id")).toList();
				if (candidates.isEmpty()) continue;
				sell = s; buy = candidates.getFirst();
				long highest = lng(buy,"price");
				if (candidates.size()>1) {
					List<Map<String,Object>> tied = candidates.stream().filter(b -> lng(b,"price") == highest).toList();
					buy = tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
				}
				break;
			}
			if (sell == null) return;
			long buyId = lng(buy,"id"), sellId = lng(sell,"id"), qty = Math.min(lng(buy,"remaining"),lng(sell,"remaining"));
			long price = buyId < sellId ? lng(buy,"price") : lng(sell,"price");
			long gross = CentralMarketRules.total(price,qty), refund = CentralMarketRules.total(lng(buy,"price"),qty) - gross;
			int buyer = (int)lng(buy,"account_id"), seller = (int)lng(sell,"account_id");
			if (buyer != 0) {
				int id;
				if (seller == 0) {
					ItemTemplate template=DataManager.ITEM_DATA.getItemTemplate((int)lng(entry,"item_id"));
					if(template==null || !key.equals(template.getTemplateId()+":0:0")) throw new SQLException("Invalid virtual supply variant");
					id=IDFactory.getInstance().nextId(); allocated.add(id);
					Item item=new Item(id,template,qty,false,0); item.setItemLocation(MARKET);item.setEquipmentSlot(65535);
					if(!InventoryDAO.insertTransactionMarketItem(c,item,buyer)) throw new SQLException("Virtual purchase could not be persisted");
					update(c,"INSERT INTO central_market_stock(item_unique_id,account_id,variant,order_id) VALUES(?,?,?,?)",id,buyer,key,buyId);
				} else {
					Map<String,Object> held=row(c,"SELECT s.item_unique_id,i.item_count FROM central_market_stock s JOIN inventory i USING(item_unique_id) WHERE s.order_id=? FOR UPDATE",sellId);
					if(held==null || lng(held,"item_count")<qty) throw new SQLException("Market custody mismatch for sale "+sellId);
					id=(int)lng(held,"item_unique_id");
					if(qty<lng(held,"item_count")) { id=split(c,id,qty,allocated);update(c,"INSERT INTO central_market_stock(item_unique_id,account_id,variant,order_id) VALUES(?,?,?,?)",id,buyer,key,buyId); }
					else update(c,"UPDATE central_market_stock SET account_id=?,order_id=? WHERE item_unique_id=?",buyer,buyId,id);
					update(c,"UPDATE inventory SET item_owner=?,slot=65535 WHERE item_unique_id=?",buyer,id);
				}
				update(c,"INSERT IGNORE INTO central_market_settlements(order_id) VALUES(?)",buyId);
				if(refund>0) credit(c,buyer,refund);
			} else {
				Map<String,Object> held=row(c,"SELECT s.item_unique_id,i.item_count FROM central_market_stock s JOIN inventory i USING(item_unique_id) WHERE s.order_id=? FOR UPDATE",sellId);
				if(held==null || lng(held,"item_count")<qty) throw new SQLException("Market custody mismatch for sale "+sellId);
				int id=(int)lng(held,"item_unique_id");
				if(qty<lng(held,"item_count")) update(c,"UPDATE inventory SET item_count=item_count-? WHERE item_unique_id=?",qty,id);
				else { update(c,"DELETE FROM item_stones WHERE item_unique_id=?",id);update(c,"DELETE FROM central_market_stock WHERE item_unique_id=?",id);update(c,"DELETE FROM inventory WHERE item_unique_id=?",id); }
			}
			if(seller!=0) {
				Map<String,Object> sellerWallet=row(c,"SELECT proceeds FROM central_market_wallet WHERE account_id=? FOR UPDATE",seller);
				CentralMarketRules.add(lng(sellerWallet,"proceeds"),gross);
				update(c,"UPDATE central_market_wallet SET proceeds=proceeds+?,version=version+1 WHERE account_id=?",gross,seller);
				update(c,"INSERT INTO central_market_settlements(order_id,gross) VALUES(?,?) ON DUPLICATE KEY UPDATE gross=gross+VALUES(gross)",sellId,gross);
			}
			for (long order : new long[]{buyId,sellId}) update(c,"UPDATE central_market_orders SET state=IF(remaining=?,'FILLED','OPEN'),remaining=remaining-? WHERE id=?",qty,qty,order);
			update(c,"INSERT INTO central_market_trades(variant,buyer_account,seller_account,quantity,unit_price,buy_order,sell_order,traded_at) VALUES(?,?,?,?,?,?,?,?)",key,buyer,seller,qty,price,buyId,sellId,System.currentTimeMillis());
			update(c,"UPDATE central_market_catalog SET traded=traded+? WHERE variant=?",qty,key);
		}
	}

	private static String cancel(Connection c, int account, long id) throws SQLException {
		Map<String,Object> order = row(c,"SELECT * FROM central_market_orders WHERE id=? AND account_id=? FOR UPDATE",id,account);
		if (order == null || !Set.of("OPEN","QUEUED").contains(str(order,"state"))) throw new IllegalArgumentException("This order has already completed or been cancelled.");
		if (str(order,"side").equals("B")) credit(c,account,CentralMarketRules.total(lng(order,"price"),lng(order,"remaining")));
		else update(c,"UPDATE central_market_stock SET order_id=NULL WHERE order_id=? AND account_id=?",id,account);
		update(c,"UPDATE central_market_orders SET state='CANCELLED' WHERE id=?",id);
		return "Order cancelled. Remaining items or Kinah returned to Market Warehouse.";
	}

	private static void credit(Connection c, int account, long amount) throws SQLException {
		long current = lng(row(c,"SELECT kinah FROM central_market_wallet WHERE account_id=? FOR UPDATE",account),"kinah");
		update(c,"UPDATE central_market_wallet SET kinah=?,version=version+1 WHERE account_id=?",CentralMarketRules.add(current,amount),account);
	}

	private static String favorite(Connection c, int account, int item) throws SQLException {
		ItemTemplate t = DataManager.ITEM_DATA.getItemTemplate(item);
		if (t == null || !eligible(t)) throw new IllegalArgumentException("This item cannot be traded.");
		if (update(c,"DELETE FROM central_market_favorites WHERE account_id=? AND item_id=?",account,item) == 0) {
			update(c,"INSERT INTO central_market_favorites VALUES(?,?)",account,item); return "Added to Favorites.";
		}
		return "Removed from Favorites.";
	}

	private static String saveSearch(Connection c,int account,Map<String,String> a) throws SQLException {
		String term=a.getOrDefault("term","").trim();
		if(term.isBlank() || term.length()>60) throw new IllegalArgumentException("Enter an item name to save.");
		if("remove".equals(a.get("mode"))) { update(c,"DELETE FROM central_market_searches WHERE account_id=? AND term=?",account,term);return "Search removed."; }
		update(c,"INSERT INTO central_market_searches VALUES(?,?,?) ON DUPLICATE KEY UPDATE created_at=VALUES(created_at)",account,term,System.currentTimeMillis());
		List<Map<String,Object>> saved=rows(c,"SELECT term FROM central_market_searches WHERE account_id=? ORDER BY created_at DESC",account);
		for(int i=10;i<saved.size();i++)update(c,"DELETE FROM central_market_searches WHERE account_id=? AND term=?",account,str(saved.get(i),"term"));
		return "Search saved.";
	}

	private static void maintain() {
		if(!ready || GameServer.isShuttingDownSoon()) return;
		try(Connection lookup=DatabaseFactory.getConnection()) {
			long now=System.currentTimeMillis();
			Set<String> keys=new LinkedHashSet<>();
			for(var r:rows(lookup,"SELECT DISTINCT variant FROM central_market_orders WHERE account_id>0 AND (state='OPEN' OR (state='QUEUED' AND available_at<=?))",now)) keys.add(str(r,"variant"));
			if(CentralMarketSimulationConfig.ENABLED && CentralMarketSimulationConfig.POPULATION>0) {
				for(var r:rows(lookup,"SELECT variant FROM central_market_simulation WHERE next_refresh<=? ORDER BY next_refresh LIMIT 40",now)) keys.add(str(r,"variant"));
				if(!simulationInitialized) {
					var fresh=rows(lookup,"SELECT c.variant FROM central_market_catalog c LEFT JOIN central_market_simulation s ON s.variant=c.variant WHERE s.variant IS NULL LIMIT 40");
					for(var r:fresh) keys.add(str(r,"variant"));
					simulationInitialized=fresh.isEmpty();
				}
			}
			for(String key:keys) {
				LOCK.lock();try {
				List<Integer> allocated=new ArrayList<>();
				try(Connection c=DatabaseFactory.getConnection()) {
					c.setAutoCommit(false);
					try {
						update(c,"UPDATE central_market_orders SET state='OPEN' WHERE variant=? AND state='QUEUED' AND available_at<=?",key,now);
						Map<String,Object> entry=row(c,"SELECT * FROM central_market_catalog WHERE variant=?",key);
						if(entry==null) continue;
						boolean real=row(c,"SELECT id FROM central_market_orders WHERE variant=? AND account_id>0 AND state='OPEN' LIMIT 1",key)!=null;
						if(real) match(c,key,allocated);
						CentralMarketSimulation.refresh(c,entry,now);
						if(real) match(c,key,allocated);
						if(lng(entry,"updated_at")<now-8*60*60*1000L) {
							var demand=row(c,"SELECT COALESCE(SUM(IF(side='B',remaining,0)),0) buys,COALESCE(SUM(IF(side='S',remaining,0)),0) sells FROM central_market_orders WHERE variant=? AND state='OPEN'",key);
							long next=CentralMarketRules.adjust(lng(entry,"base_price"),lng(entry,"floor_price"),lng(entry,"ceiling_price"),lng(demand,"buys"),lng(demand,"sells"));
							update(c,"UPDATE central_market_catalog SET previous_price=base_price,base_price=?,updated_at=? WHERE variant=?",next,now,key);
						}
						c.commit();
					} catch(Exception e) { c.rollback();for(int id:allocated) IDFactory.getInstance().releaseId(id);throw e; }
				}
				} finally { LOCK.unlock(); }
			}
			if(now>=housekeepingAt) {
				housekeepingAt=now+60_000;
				update(lookup,"DELETE FROM central_market_requests WHERE created_at<?",now-7*86400000L);
				update(lookup,"DELETE FROM central_market_orders WHERE account_id=0 AND state='CANCELLED' AND id NOT IN (SELECT buy_order FROM central_market_trades) AND id NOT IN (SELECT sell_order FROM central_market_trades) LIMIT 1000");
			}
		} catch(Exception e) { log.error("Central Market maintenance failed",e); }
	}

	private static long volume(Connection c, int account) throws SQLException {
		long result = 0;
		for (Map<String,Object> r : rows(c,"SELECT i.item_id,i.item_count FROM central_market_stock s JOIN inventory i USING(item_unique_id) WHERE s.account_id=?",account))
			result += CentralMarketRules.volume(category(DataManager.ITEM_DATA.getItemTemplate((int)lng(r,"item_id")))) * lng(r,"item_count");
		return result;
	}

	private static List<Item> marketItems(int account) throws SQLException {
		try(Connection c=DatabaseFactory.getConnection()) {
			return marketItems(c,account);
		}
	}
	private static List<Item> marketItems(Connection c,int account) throws SQLException {
		List<Item> items=new ArrayList<>();
		for(var row:rows(c,"SELECT item_unique_id FROM inventory WHERE item_owner=? AND item_location=?",account,MARKET))
			items.add(InventoryDAO.loadTransactionItem(c,(int)lng(row,"item_unique_id")));
		loadStones(c,items);return items;
	}

	private static void loadStones(Connection c,List<Item> items) throws SQLException {
		for(Item item:items) {
			for(var row:rows(c,ItemStoneListDAO.SELECT_QUERY,item.getObjectId())) {
				int id=(int)lng(row,"item_id"),slot=(int)lng(row,"slot"),category=(int)lng(row,"category");
				if(DataManager.ITEM_DATA.getItemTemplate(id)==null) throw new SQLException("Unknown market item stone");
				switch(category) {
					case 0 -> item.getItemStones().add(new com.aionemu.gameserver.model.items.ManaStone(item.getObjectId(),id,slot,PersistentState.UPDATED));
					case 2 -> item.getFusionStones().add(new com.aionemu.gameserver.model.items.ManaStone(item.getObjectId(),id,slot,PersistentState.UPDATED));
					case 1 -> { item.addGodStone(id,(int)lng(row,"proc_count"));if(item.getGodStone()==null)throw new SQLException("Invalid market Godstone");item.getGodStone().setPersistentState(PersistentState.UPDATED); }
					case 3 -> item.setIdianStone(new com.aionemu.gameserver.model.items.IdianStone(id,PersistentState.UPDATED,item,(int)lng(row,"polishNumber"),(int)lng(row,"polishCharge")));
					default -> throw new SQLException("Unknown market stone category");
				}
			}
		}
	}

	public static Map<String,Object> snapshot(Player p, Map<String,String> args) throws Exception {
		if (!ready) throw new IllegalStateException("Central Market is unavailable.");
		Object guard = p.getClientConnection();
		if (guard == null) throw new IllegalArgumentException("Log in to use Warehouse.");
		// Browsing has no custody mutations. Do not queue it behind account transfers,
		// full warehouse snapshots or market matching; actions revalidate prices.
		if("catalog".equals(args.get("section")) || "detail".equals(args.get("section"))) {
			int account=p.getAccount().getId();
			try(Connection c=DatabaseFactory.getConnection()) {
				Map<String,Object> result=new LinkedHashMap<>();
				result.put("serverTime",System.currentTimeMillis());
				List<Integer> favorites=rows(c,"SELECT item_id FROM central_market_favorites WHERE account_id=?",account).stream().map(r->(int)lng(r,"item_id")).toList();
				if("detail".equals(args.get("section"))) result.put("selected",selectedView(c,account,args.getOrDefault("variant",""),null));
				else catalogView(c,p,args,favorites,result);
				return result;
			}
		}
		synchronized (guard) {
			int account = p.getAccount().getId();
			try (Connection c = DatabaseFactory.getConnection()) {
				// A consistent database snapshot keeps wallets, claims and custody together
				// without making all other players wait for warehouse item rendering.
				c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
				c.setAutoCommit(false);
				Map<String,Object> result = new LinkedHashMap<>();
				result.put("simulatedTraders",CentralMarketSimulationConfig.ENABLED?CentralMarketSimulationConfig.POPULATION:0); result.put("player",p.getName()); result.put("serverTime",System.currentTimeMillis());
				if("detail".equals(args.get("section"))) {
					result.put("selected",selectedView(c,account,args.getOrDefault("variant",""),null));
					return result;
				}
				Map<String,Object> wallet = row(c,"SELECT * FROM central_market_wallet WHERE account_id=?",account);
				long balance = wallet == null ? 0 : lng(wallet,"kinah"), proceeds = wallet == null ? 0 : lng(wallet,"proceeds");
				result.put("balance",balance); result.put("proceeds",proceeds);
				result.put("netProceeds",CentralMarketRules.collect(proceeds,p.getAccount().getMembership()>0));
				result.put("returnPercent",p.getAccount().getMembership()>0?84.5:65);
				result.put("volume",volume(c,account)); result.put("volumeLimit",CentralMarketRules.BASE_VOLUME);
				result.put("reservedKinah",lng(row(c,"SELECT COALESCE(SUM(price*remaining),0) n FROM central_market_orders WHERE account_id=? AND side='B' AND state='OPEN'",account),"n"));
				List<Integer> favorites = rows(c,"SELECT item_id FROM central_market_favorites WHERE account_id=?",account).stream().map(r -> (int)lng(r,"item_id")).toList();
				result.put("favorites",favorites);
				result.put("savedSearches",rows(c,"SELECT term FROM central_market_searches WHERE account_id=? ORDER BY created_at DESC",account));
				result.put("notifications",rows(c,"SELECT o.id,o.variant,o.price,o.remaining,o.available_at,c.item_id,c.enchant,c.tempering FROM central_market_orders o JOIN central_market_catalog c ON c.variant=o.variant WHERE o.state='QUEUED' ORDER BY o.available_at LIMIT 100"));
				@SuppressWarnings("unchecked") List<Map<String,Object>> notifications=(List<Map<String,Object>>)result.get("notifications");
				notifications.addAll(rows(c,"SELECT t.variant,t.unit_price price,t.quantity remaining,t.traded_at available_at,IF(t.buyer_account=?,'Bought','Sold') direction,IF(t.buyer_account=?,t.buy_order,t.sell_order) order_id,c.item_id,c.enchant,c.tempering FROM central_market_trades t JOIN central_market_catalog c ON c.variant=t.variant WHERE t.buyer_account=? OR t.seller_account=? ORDER BY t.id DESC LIMIT 50",account,account,account,account));
				List<Item> held=List.of();
				if(!"activity".equals(args.get("section"))) {
				List<Map<String,Object>> storages = new ArrayList<>();
				for (int id : new int[]{0,1,2}) {
					Storage storage = p.getStorage(id);
					storages.add(Map.of("id",id,"name",id==0?"Inventory":id==1?"Character Warehouse":"Account Warehouse","limit",storage.getLimit(),"kinah",storage.getKinah(),"items",storage.getItems().stream().filter(i->!i.isEquipped() && visibleInStorage(i.getItemTemplate())).sorted(Comparator.comparingLong(Item::getEquipmentSlot).thenComparingInt(Item::getObjectId)).map(i->itemView(i,id)).toList()));
				}
				Map<Integer,Map<String,Object>> custody = rows(c,"SELECT * FROM central_market_stock WHERE account_id=?",account).stream().collect(Collectors.toMap(r->(int)lng(r,"item_unique_id"),r->r));
				held = marketItems(c,account);
				List<Map<String,Object>> marketView = new ArrayList<>();
				for (Item i : held) {
						Map<String,Object> row = custody.get(i.getObjectId()); if (row == null || !visibleInStorage(i.getItemTemplate())) continue;
						// Escrow belongs to My Orders, not transferable warehouse stock.
						if(row.get("order_id")!=null) continue;
					Map<String,Object> view = itemView(i,MARKET); view.put("reserved",row.get("order_id")!=null); view.put("order",row.get("order_id")); marketView.add(view);
				}
				storages.add(Map.of("id",MARKET,"name","Market Warehouse","limit",0,"kinah",balance,"items",marketView)); result.put("storages",storages);
				catalogView(c,p,args,favorites,result);
				}
				String of=args.getOrDefault("orderFilter","all"),hf=args.getOrDefault("historyFilter","all");
				// Keep closed orders only while their items or proceeds still need collection.
				String oc=" AND (o.state IN ('OPEN','QUEUED') OR EXISTS (SELECT 1 FROM central_market_settlements z WHERE z.order_id=o.id AND (z.gross>0 OR (o.side='B' AND o.quantity-o.remaining>z.collected_quantity))))"
					+(of.equals("B")?" AND o.side='B'":of.equals("S")?" AND o.side='S'":of.equals("open")?" AND o.state IN ('OPEN','QUEUED')":"");
				String hc=hf.equals("Bought")?"t.buyer_account=?":hf.equals("Sold")?"t.seller_account=?":"(t.buyer_account=? OR t.seller_account=?)";
				Object[] ha=hf.equals("Bought")||hf.equals("Sold")?new Object[]{account}:new Object[]{account,account};
				long ot=lng(row(c,"SELECT COUNT(*) n FROM central_market_orders o WHERE account_id=?"+oc,account),"n"),ht=lng(row(c,"SELECT COUNT(*) n FROM central_market_trades t WHERE "+hc,ha),"n"),ct=lng(row(c,"SELECT COUNT(*) n FROM central_market_collections WHERE account_id=?",account),"n");
				int op=page(args,"orderPage",ot),hp=page(args,"historyPage",hf.equals("collections")?ct:ht);
				result.put("orderTotal",ot); result.put("orderPage",op); result.put("historyTotal",hf.equals("collections")?ct:ht);result.put("historyPage",hp);
				result.put("activeOrders",lng(row(c,"SELECT COUNT(*) n FROM central_market_orders WHERE account_id=? AND state IN ('OPEN','QUEUED')",account),"n"));
				result.put("orders",rows(c,"SELECT o.*,c.item_id,c.enchant,c.tempering,COALESCE(z.gross,0) collectGross,IF(o.side='B',GREATEST(0,o.quantity-o.remaining-COALESCE(z.collected_quantity,o.quantity-o.remaining)),0) collectQuantity FROM central_market_orders o JOIN central_market_catalog c ON c.variant=o.variant LEFT JOIN central_market_settlements z ON z.order_id=o.id WHERE o.account_id=?"+oc+" ORDER BY (COALESCE(z.gross,0)>0 OR (o.side='B' AND o.quantity-o.remaining>COALESCE(z.collected_quantity,o.quantity-o.remaining))) DESC,o.created_at DESC,o.id DESC LIMIT 50 OFFSET "+(op-1)*50,account));
				List<Object> historyArgs=new ArrayList<>();historyArgs.add(account);Collections.addAll(historyArgs,ha);
				result.put("history",rows(c,"SELECT t.*,c.item_id,c.enchant,c.tempering,IF(buyer_account=?,'Bought','Sold') direction FROM central_market_trades t JOIN central_market_catalog c ON c.variant=t.variant WHERE "+hc+" ORDER BY t.id DESC LIMIT 50 OFFSET "+(hp-1)*50,historyArgs.toArray()));
				result.put("collections",rows(c,"SELECT gross,net,collected_at FROM central_market_collections WHERE account_id=? ORDER BY id DESC LIMIT 50 OFFSET "+(hp-1)*50,account));
				for (String section : new String[]{"orders","history","notifications"}) {
					@SuppressWarnings("unchecked") List<Map<String,Object>> records = (List<Map<String,Object>>)result.get(section);
					for (Map<String,Object> record : records) { ItemTemplate t=DataManager.ITEM_DATA.getItemTemplate((int)lng(record,"item_id")); record.put("name",t.getName()); }
				}
				if(!"activity".equals(args.get("section"))) result.put("selected",selectedView(c,account,args.getOrDefault("variant",""),held));
				result.put("alwaysMax",CentralMarketPreferences.load(c,account));c.commit();return result;
			}
		}
	}

	private static String catalogCategory(ItemTemplate t) {
		String cached=catalogCategories.get(t.getTemplateId());
		return cached==null?category(t):cached;
	}

	static void catalogView(Connection c, Player p, Map<String,String> args, List<Integer> favorites, Map<String,Object> result) throws SQLException {
		CentralMarketBrowse.view(c,p,args,favorites,result,templates);
	}

	static Map<Integer,Map<String,Object>> catalogPrices(Connection c,List<ItemTemplate> items) throws SQLException {
		if(items.isEmpty()) return Map.of();
		// IDs come from loaded templates, never from request text. Restrict all aggregates to this page/filter.
		String ids=items.stream().map(t->Integer.toString(t.getTemplateId())).collect(Collectors.joining(","));
		return rows(c,"SELECT c.item_id,c.base_price,c.previous_price,COALESCE(t.traded,0) traded,COALESCE(s.stock,0) stock FROM central_market_catalog c LEFT JOIN (SELECT item_id,SUM(traded) traded FROM central_market_catalog WHERE item_id IN ("+ids+") GROUP BY item_id) t ON t.item_id=c.item_id LEFT JOIN (SELECT v.item_id,SUM(o.remaining) stock FROM central_market_orders o JOIN central_market_catalog v ON v.variant=o.variant WHERE v.item_id IN ("+ids+") AND o.side='S' AND o.state='OPEN' GROUP BY v.item_id) s ON s.item_id=c.item_id WHERE c.item_id IN ("+ids+") AND c.variant=CONCAT(c.item_id,':0:0')")
			.stream().collect(Collectors.toMap(r->(int)lng(r,"item_id"),r->r));
	}

	private static Map<String,Object> selectedView(Connection c,int account,String key,List<Item> held) throws SQLException {
		if (!key.isBlank()) {
			if(key.matches("[0-9]{9}:[0-9]{1,2}:[0-9]{1,2}")) {
				String[] parts=key.split(":"); ItemTemplate t=DataManager.ITEM_DATA.getItemTemplate(Integer.parseInt(parts[0]));
				int enchant=Integer.parseInt(parts[1]),temper=Integer.parseInt(parts[2]);
				if(t!=null && eligible(t) && enchant<=t.getMaxEnchantLevel() && temper<=t.getMaxTampering()) catalog(c,t,key,enchant,temper);
			}
			Map<String,Object> entry=row(c,"SELECT * FROM central_market_catalog WHERE variant=?",key);
			if(entry!=null){
				ItemTemplate selectedTemplate=DataManager.ITEM_DATA.getItemTemplate((int)lng(entry,"item_id"));
				if(entry.get("attributes_json")!=null) entry.put("attributes",com.alibaba.fastjson2.JSON.parseObject(str(entry,"attributes_json")));
				entry.remove("attributes_json");
				entry.put("levels",CentralMarketRules.ladder(lng(entry,"base_price"),lng(entry,"floor_price"),lng(entry,"ceiling_price")));
				entry.put("book",rows(c,"SELECT side,price,SUM(remaining) quantity,SUM(IF(account_id<>?,remaining,0)) available_quantity FROM central_market_orders WHERE variant=? AND state='OPEN' GROUP BY side,price ORDER BY price DESC",account,key));
				List<Map<String,Object>> variants=rows(c,"SELECT variant,enchant,tempering FROM central_market_catalog WHERE item_id=? ORDER BY enchant,tempering,variant",lng(entry,"item_id"));
				int currentTemper=(int)lng(entry,"tempering");
				for(int enchant=0;enchant<=selectedTemplate.getMaxEnchantLevel();enchant++) {
					String plain=selectedTemplate.getTemplateId()+":"+enchant+":"+currentTemper;
					if(variants.stream().noneMatch(v->str(v,"variant").equals(plain))) variants.add(new LinkedHashMap<>(Map.of("variant",plain,"enchant",enchant,"tempering",currentTemper)));
				}
				for(int temper=0;temper<=selectedTemplate.getMaxTampering();temper++) {
					String plain=selectedTemplate.getTemplateId()+":"+lng(entry,"enchant")+":"+temper;
					if(variants.stream().noneMatch(v->str(v,"variant").equals(plain)))variants.add(new LinkedHashMap<>(Map.of("variant",plain,"enchant",lng(entry,"enchant"),"tempering",temper)));
				}
				variants.sort(Comparator.<Map<String,Object>>comparingLong(v->lng(v,"enchant")).thenComparingLong(v->lng(v,"tempering")).thenComparing(v->str(v,"variant")));entry.put("variants",variants);
				entry.put("chart",rows(c,"SELECT FLOOR(traded_at/86400000) day,ROUND(SUM(unit_price*quantity)/SUM(quantity)) price,SUM(quantity) volume FROM central_market_trades WHERE variant=? AND traded_at>? GROUP BY day ORDER BY day",key,System.currentTimeMillis()-30*86400000L));
				entry.put("queue",rows(c,"SELECT price,SUM(remaining) quantity,MIN(available_at) available_at FROM central_market_orders WHERE variant=? AND state='QUEUED' GROUP BY price",key));
				entry.putAll(templateView(DataManager.ITEM_DATA.getItemTemplate((int)lng(entry,"item_id"))));
				Item example=held == null ? null : held.stream().filter(i->variant(i).equals(key)).findFirst().orElse(null);
				if(example==null) {
					Map<String,Object> sample=row(c,"SELECT item_unique_id FROM central_market_stock WHERE variant=? ORDER BY (account_id=?) DESC LIMIT 1",key,account);
					if(sample!=null) {
						example=InventoryDAO.loadTransactionItem(c,(int)lng(sample,"item_unique_id"));
						if(example!=null) loadStones(c,List.of(example));
					}
				}
				if(example!=null) entry.put("attributes",itemView(example,MARKET).get("attributes"));
				return entry;
			}
		}
		return null;
	}

	static Map<String,Object> templateView(ItemTemplate t) {
		Map<String,Object> v=new LinkedHashMap<>();
		v.put("item_id",t.getTemplateId()); v.put("name",t.getName()); v.put("category",category(t));
		v.put("group",t.getItemGroup().name()); v.put("level",t.getLevel()); v.put("race",t.getRace().name());
		v.put("quality",t.getItemQuality()==null?"COMMON":t.getItemQuality().name());
		v.put("volume",CentralMarketRules.volume(category(t))); v.put("maxQuantity",t.isStackable()?Math.min(1000,t.getMaxStackCount()):1);
		List<Map<String,Object>> stats=new ArrayList<>();
		if(t.getModifiers()!=null) t.getModifiers().stream().filter(s->s.getName()!=null).forEach(s->stats.add(Map.of("name",statName(s.getName().name()),"value",s.getValue())));
		if(t.isWeapon() && t.getWeaponStats()!=null) { v.put("damage",t.getWeaponStats().getMinDamage()+"–"+t.getWeaponStats().getMaxDamage()); }
		v.put("stats",stats); v.put("sockets",t.getManastoneSlots()); return v;
	}

	private static Map<String,Object> itemView(Item i,int source) {
		Map<String,Object> v=templateView(i.getItemTemplate());
		v.put("object",i.getObjectId()); v.put("source",source); v.put("quantity",i.getItemCount());
		v.put("name",i.getItemName()); v.put("enchant",i.getEnchantLevel()); v.put("tempering",i.getTempering()); v.put("variant",variant(i));
		v.put("tradeable",i.isTradeable() && eligible(i.getItemTemplate()));
		Map<String,Object> attributes=new LinkedHashMap<>();
		attributes.put("Enchantment",i.getEnchantLevel()); attributes.put("Tempering",i.getTempering());
		if(i.getItemSkinTemplate().getTemplateId()!=i.getItemId())attributes.put("Appearance",i.getItemSkinTemplate().getName());
		if(i.getFusionedItemId()!=0)attributes.put("Armsfusion",i.getFusionedItemTemplate().getName());
		if(i.getFusionedItemId()!=0 && i.getFusionedItemBonusStatsId()!=0)attributes.put("Armsfusion bonuses",bonusDescription(i.getFusionedItemTemplate(),i.getFusionedItemBonusStatsId()));
		if(i.getGodStone()!=null)attributes.put("Godstone",i.getGodStone().getItemTemplate().getName());
		if(i.hasManaStones())attributes.put("Manastones",i.getItemStones().stream().map(s->s.getItemTemplate().getName()).collect(Collectors.joining(", ")));
		if(i.isSoulBound())attributes.put("Soul Bound","Yes");
		if(i.isAmplified())attributes.put("Amplified","Yes");
		if((i.getTuneCount()!=0 && i.getTuneCount()!=-1))attributes.put("Retuning",i.getTuneCount());
		if(i.getBonusStatsId()!=0)attributes.put("Random bonuses",bonusDescription(i.getItemTemplate(),i.getBonusStatsId()));
		if(!i.getFusionStones().isEmpty())attributes.put("Armsfusion Manastones",i.getFusionStones().stream().map(s->s.getItemTemplate().getName()).collect(Collectors.joining(", ")));
		if(i.getIdianStone()!=null)attributes.put("Idian",i.getIdianStone().getItemTemplate().getName()+" ("+i.getIdianStone().getPolishCharge()+" charge)");
		if(i.getItemColor()!=null)attributes.put("Dye",String.format("#%06X",i.getItemColor()&0xffffff));
		v.put("attributes",attributes); CentralMarketPreferences.decorateItem(v,i,source);return v;
	}

	private static String bonusDescription(ItemTemplate template,int bonus) {
		var stats=DataManager.ITEM_RANDOM_BONUSES.getTemplate(com.aionemu.gameserver.model.templates.item.bonuses.StatBonusType.INVENTORY,template.getStatBonusSetId(),bonus).getModifiers();
		return stats.stream().map(s->statName(s.getName().name())+" "+s.getValue()).collect(Collectors.joining(", "));
	}

	private static String statName(String name) {
		return switch (name) {
			case "MAXHP" -> "HP"; case "MAXMP" -> "MP"; case "MAXDP" -> "DP";
			case "PHYSICAL_ATTACK" -> "Attack"; case "PHYSICAL_ACCURACY" -> "Accuracy";
			case "PHYSICAL_CRITICAL" -> "Crit Strike"; case "MAGICAL_CRITICAL" -> "Crit Spell";
			case "MAGICAL_ACCURACY" -> "Magic Accuracy"; case "MAGICAL_RESIST" -> "Magic Resist";
			case "BOOST_MAGICAL_SKILL", "BOOST_SPELL_ATTACK" -> "Magic Boost";
			case "MAGIC_SKILL_BOOST_RESIST" -> "Magic Suppression"; case "MAGICAL_DEFEND" -> "Magic Defense";
			case "FLY_TIME" -> "Flight Time"; case "FLY_SPEED" -> "Flight Speed"; case "SPEED", "ALLSPEED" -> "Movement Speed";
			case "BOOST_CASTING_TIME" -> "Casting Speed"; case "BOOST_HATE" -> "Enmity Boost"; case "HEAL_BOOST" -> "Healing Boost";
			case "PHYSICAL_CRITICAL_RESIST" -> "Strike Resist"; case "MAGICAL_CRITICAL_RESIST" -> "Spell Resist";
			case "PHYSICAL_CRITICAL_DAMAGE_REDUCE" -> "Strike Fortitude"; case "MAGICAL_CRITICAL_DAMAGE_REDUCE" -> "Spell Fortitude";
			default -> {
				String[] words=name.toLowerCase(Locale.ROOT).replace('_',' ').split(" ");
				for(int i=0;i<words.length;i++) if(!words[i].isEmpty()) words[i]=Character.toUpperCase(words[i].charAt(0))+words[i].substring(1);
				yield String.join(" ",words);
			}
		};
	}

	static int update(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement s=c.prepareStatement(sql)) { bind(s,args); return s.executeUpdate(); }
	}
	static void bind(PreparedStatement s, Object... args) throws SQLException { for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]); }
	static List<Map<String,Object>> rows(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement s=c.prepareStatement(sql)) {
			bind(s,args); try(ResultSet r=s.executeQuery()) {
				List<Map<String,Object>> result=new ArrayList<>(); int count=r.getMetaData().getColumnCount();
				while(r.next()) { Map<String,Object> row=new LinkedHashMap<>(); for(int i=1;i<=count;i++) row.put(r.getMetaData().getColumnLabel(i),r.getObject(i)); result.add(row); }
				return result;
			}
		}
	}
	static Map<String,Object> row(Connection c,String sql,Object... args) throws SQLException { List<Map<String,Object>> rows=rows(c,sql,args); return rows.isEmpty()?null:rows.getFirst(); }
	static long lng(Map<String,Object> r,String key) { return ((Number)r.get(key)).longValue(); }
	static String str(Map<String,Object> r,String key) { return String.valueOf(r.get(key)); }
	static long number(Map<String,String> a,String key) { try{return Long.parseLong(a.getOrDefault(key,""));}catch(Exception e){throw new IllegalArgumentException("Enter a valid " + key + ".");} }
	private static int page(Map<String,String> a,String key,long total) {
		try{return (int)Math.max(1,Math.min(Long.parseLong(a.getOrDefault(key,"1")),(total+49)/50));}catch(Exception e){return 1;}
	}
}
