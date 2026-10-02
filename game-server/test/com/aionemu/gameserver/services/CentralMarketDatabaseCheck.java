package com.aionemu.gameserver.services;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.aionemu.commons.configs.DatabaseConfig;
import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.utils.idfactory.IDFactory;

/** Integration checks in a NEW, empty schema. Never writes player data in the live schema. */
public final class CentralMarketDatabaseCheck {
	private static String schema;
	private static int assertions;
	private static final Method MATCH, CANCEL;
	static {
		try { MATCH=CentralMarketService.class.getDeclaredMethod("match",Connection.class,String.class,List.class);MATCH.setAccessible(true);
			CANCEL=CentralMarketService.class.getDeclaredMethod("cancel",Connection.class,int.class,long.class);CANCEL.setAccessible(true);
		} catch(Exception e){throw new ExceptionInInitializerError(e);}
	}
	static void check(boolean value,String label) { assertions++;if(!value)throw new AssertionError(label); }
	static void match(Connection c,String key,List<Integer> ids)throws Exception { try{MATCH.invoke(null,c,key,ids);}catch(InvocationTargetException e){throw (Exception)e.getCause();} }
	static long value(Connection c,String sql,Object... args)throws SQLException { return CentralMarketService.lng(CentralMarketService.row(c,sql,args),"n"); }
	static void set(Object target,String name,Object value)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
	static com.aionemu.gameserver.model.gameobjects.Item batchItem(int object,com.aionemu.gameserver.model.templates.item.ItemTemplate template,long count) {
		return new com.aionemu.gameserver.model.gameobjects.Item(object,template,count,false,0);
	}
	static void batchTransfers(Connection c)throws Exception {
		var template=new com.aionemu.gameserver.model.templates.item.ItemTemplate();set(template,"itemId",160000001);set(template,"name","Batch transfer check");set(template,"maxStackCount",100);set(template,"price",100);set(template,"mask",2);
		check(CentralMarketService.visibleInStorage(template),"normal items remain in storage tabs");
		set(template,"itemGroup",com.aionemu.gameserver.model.templates.item.enums.ItemGroup.QUEST);
		check(!CentralMarketService.visibleInStorage(template)&&!CentralMarketService.eligible(template),"quest items are excluded even without special inventory metadata");
		set(template,"itemGroup",com.aionemu.gameserver.model.templates.item.enums.ItemGroup.NONE);
		var extra=new com.aionemu.gameserver.model.templates.item.ExtraInventory();set(extra,"id",1);set(template,"extraInventory",extra);
		check(!CentralMarketService.visibleInStorage(template),"dedicated special inventory items stay outside market storage tabs");set(template,"extraInventory",null);
		check(CentralMarketService.batchEntries("10:5,11:8").equals(Map.of(10,5L,11,8L)),"batch counts retain the selected full stack quantities");
		for(String invalid:List.of("", "10:5,10:6", "10:0", "-1:3", "2147483648:1", "10:2,", String.join(",",Collections.nCopies(301,"10:1")))) {
			boolean rejected=false;try{CentralMarketService.batchEntries(invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid or oversized batch is refused");
		}
		var a=batchItem(10030001,template,10);var b=batchItem(10030002,template,15);var existing=batchItem(10030003,template,25);
		var merged=CentralMarketService.planBatch(List.of(a,b),List.of(existing),0,false);
		check(merged.additions().isEmpty()&&merged.counts().get(existing)==50&&merged.merges().size()==2,"multiple sources merge into one destination without a free slot");
		check(existing.getItemCount()==25&&a.getItemCount()==10&&b.getItemCount()==15,"batch planning does not mutate live item objects");
		var added=CentralMarketService.planBatch(List.of(a,b),List.of(),1,false);
		check(added.additions().equals(List.of(a))&&added.counts().get(a)==25&&added.merges().get(b)==a,"later stacks merge into an earlier planned addition");
		boolean full=false;try{CentralMarketService.planBatch(List.of(batchItem(10030004,template,90),b),List.of(),1,false);}catch(IllegalArgumentException e){full=true;}check(full,"cumulative stack limits cannot bypass destination capacity");
		b.setSoulBound(true);full=false;try{CentralMarketService.planBatch(List.of(b),List.of(existing),0,false);}catch(IllegalArgumentException e){full=true;}check(full,"soul-bound and unbound stacks cannot merge");b.setSoulBound(false);
		var market=CentralMarketService.planBatch(List.of(a,b),List.of(),0,true);check(market.additions().size()==2,"Market Warehouse retains separate custody rows");
		c.setAutoCommit(false);
		for(var item:List.of(a,b,existing))exec(c,"INSERT INTO inventory(item_unique_id,item_id,item_count,item_owner,item_location,item_creator) VALUES(?,160000001,?,?,?,'Batch check')",item.getObjectId(),item.getItemCount(),item==existing?201:701,item==existing?2:0);
		c.commit();
		CentralMarketService.persistBatch(c,List.of(a,b),merged,0,2,201,201);c.commit();
		check(value(c,"SELECT COUNT(*) n FROM inventory WHERE item_unique_id IN (10030001,10030002)")==0&&value(c,"SELECT item_count n FROM inventory WHERE item_unique_id=10030003")==50,"batch merge persists exact total with no duplicate source rows");
		exec(c,"UPDATE inventory SET item_count=10 WHERE item_unique_id=10030003");
		exec(c,"INSERT INTO inventory(item_unique_id,item_id,item_count,item_owner,item_location,item_creator,enchant,item_skin) VALUES(10030001,160000001,10,701,0,'Batch check',7,110900001),(10030002,160000001,15,701,0,'Batch check',7,110900001)");c.commit();
		CentralMarketService.persistBatch(c,List.of(a,b),market,0,125,201,201);c.commit();
		check(value(c,"SELECT COUNT(*) n FROM central_market_stock WHERE account_id=201")==2&&value(c,"SELECT SUM(item_count) n FROM inventory WHERE item_owner=201 AND item_location=125")==25,"batch deposit transfers ownership and custody for every stack");
		check(value(c,"SELECT COUNT(*) n FROM inventory WHERE item_location=125 AND enchant=7 AND item_skin=110900001 AND item_creator='Batch check'")==2,"batch custody preserves enhancement, appearance and creator columns");
		CentralMarketService.persistBatch(c,List.of(a,b),market,125,1,201,701);c.commit();
		check(value(c,"SELECT COUNT(*) n FROM central_market_stock WHERE account_id=201")==0&&value(c,"SELECT SUM(item_count) n FROM inventory WHERE item_owner=701 AND item_location=1")==25,"batch withdrawal releases market custody into character storage");
		CentralMarketService.persistBatch(c,List.of(a,b),market,1,2,201,201);c.commit();
		check(value(c,"SELECT COUNT(*) n FROM inventory WHERE item_unique_id IN (10030001,10030002) AND item_owner=201 AND item_location=2")==2,"character-to-account batch uses account ownership");
		CentralMarketService.persistBatch(c,List.of(a,b),market,2,0,201,701);c.commit();
		check(value(c,"SELECT COUNT(*) n FROM inventory WHERE item_unique_id IN (10030001,10030002) AND item_owner=701 AND item_location=0")==2,"account-to-inventory batch uses character ownership");
		exec(c,"INSERT INTO central_market_stock(item_unique_id,account_id,variant) VALUES(10030002,201,?)",CentralMarketService.variant(b));c.commit();
		boolean failed=false;try{CentralMarketService.persistBatch(c,List.of(a,b),market,0,125,201,201);}catch(SQLException e){failed=true;c.rollback();}
		check(failed&&value(c,"SELECT COUNT(*) n FROM inventory WHERE item_unique_id IN (10030001,10030002) AND item_owner=701 AND item_location=0")==2,"failure after the first custody update rolls back the entire batch");
		check(value(c,"SELECT COUNT(*) n FROM central_market_stock WHERE item_unique_id=10030001")==0,"rollback leaves no partial market custody entry");
		exec(c,"DELETE FROM central_market_stock WHERE account_id=201");exec(c,"DELETE FROM inventory WHERE item_unique_id IN (10030001,10030002,10030003)");exec(c,"DELETE FROM central_market_catalog WHERE variant=?",CentralMarketService.variant(a));c.commit();c.setAutoCommit(true);
	}
	static void catalogPage(Connection c) throws Exception {
		int id=100000096, other=100000097;
		for(String key:List.of(id+":0:0",id+":1:0",other+":0:0")) {
			exec(c,"INSERT INTO central_market_catalog(variant,item_id,enchant,tempering,base_price,floor_price,ceiling_price,previous_price,traded) VALUES(?,?,0,0,100,1,1000,90,7)",key,key.startsWith(id+":")?id:other);
		}
		order(c,111,id+":0:0","S",100,3); order(c,112,id+":1:0","S",100,5);
		order(c,113,id+":0:0","B",100,99); long cancelled=order(c,114,id+":0:0","S",100,55);
		exec(c,"UPDATE central_market_orders SET state='CANCELLED' WHERE id=?",cancelled);
		var template=new com.aionemu.gameserver.model.templates.item.ItemTemplate();
		Field itemId=template.getClass().getDeclaredField("itemId");itemId.setAccessible(true);itemId.set(template,id);
		var prices=CentralMarketService.catalogPrices(c,List.of(template));
		check(prices.size()==1 && prices.containsKey(id) && !prices.containsKey(other),"price query returns only requested page");
		check(CentralMarketService.lng(prices.get(id),"stock")==8,"stock includes open sales across variants only");
		check(CentralMarketService.lng(prices.get(id),"traded")==14,"trade count includes every variant");
		check(CentralMarketService.lng(prices.get(id),"base_price")==100 && CentralMarketService.lng(prices.get(id),"previous_price")==90,"base price comes from plain variant");
		check(CentralMarketService.catalogPrices(c,List.of()).isEmpty(),"empty catalog page performs no query");
		Field name=template.getClass().getDeclaredField("name");name.setAccessible(true);name.set(template,"Catalog read item");
		Field templates=CentralMarketService.class.getDeclaredField("templates");templates.setAccessible(true);
		Object previous=templates.get(null);
		try {
			templates.set(null,List.of(template));
			Map<String,Object> result=new LinkedHashMap<>();
			CentralMarketService.catalogView(c,null,Map.of("q","Catalog","category","Other"),List.of(),result);
			check(result.keySet().equals(Set.of("catalog","page","total","subcategories")),"catalog read contains no warehouse, wallet or history data");
			check(((List<?>)result.get("catalog")).size()==1 && ((Number)result.get("total")).intValue()==1,"catalog search and category retain matching item");
			result.clear();CentralMarketService.catalogView(c,null,Map.of("filter","favorites"),List.of(),result);
			check(((List<?>)result.get("catalog")).isEmpty(),"catalog favorites filter excludes unsaved items");
			result.clear();CentralMarketService.catalogView(c,null,Map.of("filter","favorites"),List.of(id),result);
			check(((List<?>)result.get("catalog")).size()==1,"catalog favorites filter keeps saved items");
		} finally { templates.set(null,previous); }
		exec(c,"DELETE FROM central_market_orders WHERE account_id BETWEEN 111 AND 114");
		exec(c,"DELETE FROM central_market_catalog WHERE item_id IN (?,?)",id,other);
	}
	static int exec(Connection c,String sql,Object... args)throws SQLException {return CentralMarketService.update(c,sql,args);}
	static void virtualOrders(Connection c) throws Exception {
		var data=new com.aionemu.gameserver.dataholders.ItemData();
		var template=new com.aionemu.gameserver.model.templates.item.ItemTemplate();set(template,"itemId",160000001);set(template,"name","Simulation check");set(template,"maxStackCount",100);set(template,"price",100);set(template,"mask",2);
		set(data,"items",new HashMap<>(Map.of(160000001,template)));
		var previous=com.aionemu.gameserver.dataholders.DataManager.ITEM_DATA;
		com.aionemu.gameserver.dataholders.DataManager.ITEM_DATA=data;
		try {
			String key="160000001:0:0";catalog(c,key,1000);
			long sale=order(c,0,key,"S",100,10), demand=order(c,0,key,"B",90,10);
			long buy=order(c,2,key,"B",100,4);exec(c,"UPDATE central_market_wallet SET kinah=kinah-400 WHERE account_id=2");
			match(c,key,new ArrayList<>());c.commit();
			check(value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",buy)==0,"visible virtual stock fills a funded buy in the matching transaction");
			check(value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",sale)==6,"virtual supply is finite and decrements by the exact purchased amount");
			check(value(c,"SELECT SUM(i.item_count) n FROM central_market_stock s JOIN inventory i USING(item_unique_id) WHERE s.order_id=?",buy)==4,"virtual purchase creates exact item custody for collection");
			CentralMarketSettlement.collect(c,2,buy,false);c.commit();
			long wait=order(c,3,key,"B",95,1);match(c,key,new ArrayList<>());c.commit();
			check(value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",wait)==1,"waiting demand below an available ask is not sale stock");
			long playerSale=order(c,1,key,"S",90,3);int held=stock(c,1,key,playerSale,3);match(c,key,new ArrayList<>());c.commit();
			check(value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",wait)==0,"sale first fills a higher player preorder");
			check(value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",demand)==8,"remaining sale fills simulated demand immediately");
			check(value(c,"SELECT COUNT(*) n FROM inventory WHERE item_unique_id=?",held)==0,"simulated buyer consumes the sold custody without a bot inventory");
			check(value(c,"SELECT COUNT(*) n FROM central_market_wallet WHERE account_id=0")==0,"simulation never creates an account zero wallet");
			check(value(c,"SELECT COUNT(*) n FROM central_market_trades WHERE buyer_account=0 AND seller_account=0")==0,"simulation never trades with itself");
			// Legacy purchase receipts already in the warehouse must not be claimable twice.
			String old="legacy-collection";catalog(c,old,1000);long oldBuy=order(c,4,old,"B",100,2),oldSale=order(c,5,old,"S",100,2);
			exec(c,"UPDATE central_market_orders SET remaining=0,state='FILLED' WHERE id IN (?,?)",oldBuy,oldSale);
			exec(c,"INSERT INTO central_market_trades(variant,buyer_account,seller_account,quantity,unit_price,buy_order,sell_order,traded_at) VALUES(?,4,5,2,100,?,?,0)",old,oldBuy,oldSale);
			exec(c,"UPDATE central_market_wallet SET proceeds=75 WHERE account_id=5");c.commit();
			CentralMarketSettlement.migrate(c);c.commit();CentralMarketSettlement.migrate(c);c.commit();
			check(value(c,"SELECT collected_quantity n FROM central_market_settlements WHERE order_id=?",oldBuy)==2,"legacy delivered purchases migrate as already collected");
			check(value(c,"SELECT gross n FROM central_market_settlements WHERE order_id=?",oldSale)==75,"migration assigns only outstanding legacy sale proceeds");
			check(value(c,"SELECT gross n FROM central_market_settlements WHERE order_id=?",playerSale)==275,"migration leaves current uncollected sale proceeds intact");
			CentralMarketSettlement.collect(c,5,oldSale,true);c.commit();
			check(value(c,"SELECT proceeds n FROM central_market_wallet WHERE account_id=5")==0,"legacy remaining proceeds collect once using per-order receipts");
		} finally { com.aionemu.gameserver.dataholders.DataManager.ITEM_DATA=previous; }
	}
	static void catalog(Connection c,String key,long ceiling)throws SQLException {exec(c,"INSERT INTO central_market_catalog(variant,item_id,enchant,tempering,base_price,floor_price,ceiling_price,previous_price) VALUES(?,160000001,0,0,100,1,?,100)",key,ceiling);}
	static long order(Connection c,int account,String key,String side,long price,long qty)throws SQLException {
		try(PreparedStatement s=c.prepareStatement("INSERT INTO central_market_orders(account_id,variant,side,price,quantity,remaining,state,created_at,available_at) VALUES(?,?,?,?,?,?,'OPEN',?,0)",Statement.RETURN_GENERATED_KEYS)){
			CentralMarketService.bind(s,account,key,side,price,qty,qty,System.currentTimeMillis());s.executeUpdate();try(ResultSet r=s.getGeneratedKeys()){r.next();return r.getLong(1);}
		}
	}
	static int stock(Connection c,int account,String key,long order,long qty)throws SQLException {
		int id=IDFactory.getInstance().nextId();
		exec(c,"INSERT INTO inventory(item_unique_id,item_id,item_count,item_owner,item_location,enchant,tempering,item_color,item_creator,item_skin) VALUES(?,160000001,?,?,125,15,5,11259375,'Custody check',110900001)",id,qty,account);
		exec(c,"INSERT INTO central_market_stock VALUES(?,?,?,?)",id,account,key,order);return id;
	}
	public static void main(String[] args)throws Exception {
		Path root=Path.of(args[0]).toAbsolutePath();Properties p=new Properties();
		try(var in=Files.newInputStream(root.resolve("config/network/database.properties"))){p.load(in);}
		Path overrides=root.resolve("config/mygs.properties");if(Files.exists(overrides))try(var in=Files.newInputStream(overrides)){p.load(in);}
		String url=p.getProperty("database.url").replace("${gameserver.timezone}","UTC"), user=p.getProperty("database.user"), password=p.getProperty("database.password");
		schema="aion_cm_check_"+System.currentTimeMillis();check(schema.matches("aion_cm_check_[0-9]+"),"isolated schema name");
		try(Connection live=DriverManager.getConnection(url,user,password);Statement s=live.createStatement()){
			String source=live.getCatalog();check(source!=null&&!source.equals(schema),"live schema is only read");
			s.execute("CREATE DATABASE `"+schema+"`");
			final String testSchema=schema;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try(Connection cleanup=DriverManager.getConnection(url,user,password);Statement drop=cleanup.createStatement()) {
					drop.execute("DROP DATABASE `"+testSchema+"`");
				} catch(SQLException e) { System.err.println("Could not remove isolated test schema: "+testSchema); }
			},"market-test-cleanup"));
			List<String> tables=new ArrayList<>();try(ResultSet r=live.getMetaData().getTables(source,null,"%",new String[]{"TABLE"})){while(r.next())tables.add(r.getString("TABLE_NAME"));}
			for(String table:tables) {check(table.matches("[a-zA-Z0-9_]+"),"safe fixture table");s.execute("CREATE TABLE `"+schema+"`.`"+table+"` LIKE `"+source+"`.`"+table+"`");}
			DatabaseConfig.DATABASE_URL=url.replace("/"+source,"/"+schema);check(!DatabaseConfig.DATABASE_URL.equals(url),"test pool cannot point to live data");
		}
		DatabaseConfig.DATABASE_USER=user;DatabaseConfig.DATABASE_PASSWORD=password;DatabaseConfig.DATABASE_CONNECTIONS_MAX=5;DatabaseConfig.DATABASE_TIMEOUT=5000;DatabaseFactory.init();
		try(Connection c=DatabaseFactory.getConnection();Statement s=c.createStatement()){
			for(String sql:Files.readString(Path.of("game-server/config/central-market/schema.sql")).split(";"))if(!sql.isBlank())s.execute(sql);
			check(value(c,"SELECT COUNT(*) n FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'central_market_%' AND ENGINE='InnoDB'")==11,"all eleven market tables use transactional storage");
			batchTransfers(c);catalogPage(c);
			for(int account=1;account<=8;account++)exec(c,"INSERT INTO central_market_wallet VALUES(?,5000,0,0)",account);
			catalog(c,"partial",1000);long sale=order(c,1,"partial","S",100,15);int original=stock(c,1,"partial",sale,15);
			exec(c,"INSERT INTO item_stones(item_unique_id,item_id,slot,category,polishNumber,polishCharge,proc_count) VALUES(?,167000001,0,0,0,0,0)",original);
			long buy=order(c,2,"partial","B",105,10);exec(c,"UPDATE central_market_wallet SET kinah=kinah-1050 WHERE account_id=2");
			c.setAutoCommit(false);List<Integer> ids=new ArrayList<>();match(c,"partial",ids);c.commit();
			check(value(c,"SELECT SUM(i.item_count) n FROM inventory i JOIN central_market_stock s USING(item_unique_id) WHERE s.account_id=2")==10,"partial fill delivers exactly ten items");
			check(value(c,"SELECT item_count n FROM inventory WHERE item_unique_id=?",original)==5,"remaining sale retains five items");
			check(value(c,"SELECT kinah n FROM central_market_wallet WHERE account_id=2")==4000,"buyer receives fifty Kinah price improvement");
			check(value(c,"SELECT proceeds n FROM central_market_wallet WHERE account_id=1")==1000,"seller has gross proceeds to collect");
			check(value(c,"SELECT COUNT(*) n FROM central_market_orders WHERE id=? AND state='FILLED' AND remaining=0",buy)==1,"filled order state is correct");
			check(value(c,"SELECT COUNT(*) n FROM central_market_stock WHERE account_id=2 AND order_id=?",buy)==1,"purchased items remain reserved until this buy order is collected");
			long balance=value(c,"SELECT kinah n FROM central_market_wallet WHERE account_id=2");
			CentralMarketSettlement.collect(c,2,buy,false);c.rollback();
			check(value(c,"SELECT COUNT(*) n FROM central_market_stock WHERE account_id=2 AND order_id=?",buy)==1,"rolled back collection keeps purchased custody reserved");
			CentralMarketSettlement.collect(c,2,buy,false);c.commit();
			check(value(c,"SELECT COUNT(*) n FROM central_market_stock WHERE account_id=2 AND order_id IS NULL")==1,"collect releases bought items to usable warehouse stock");
			check(value(c,"SELECT kinah n FROM central_market_wallet WHERE account_id=2")==balance,"item collection never charges again");
			boolean duplicate=false;try{CentralMarketSettlement.collect(c,2,buy,false);}catch(IllegalArgumentException e){duplicate=true;}check(duplicate,"repeated item collection cannot duplicate items");
			boolean foreign=false;try{CentralMarketSettlement.collect(c,3,sale,false);}catch(IllegalArgumentException e){foreign=true;}check(foreign,"other accounts cannot collect sale proceeds");
			CentralMarketSettlement.collect(c,1,sale,false);c.commit();
			check(value(c,"SELECT proceeds n FROM central_market_wallet WHERE account_id=1")==0 && value(c,"SELECT kinah n FROM central_market_wallet WHERE account_id=1")==5650,"partial sale collection credits exactly its taxed proceeds");
			duplicate=false;try{CentralMarketSettlement.collect(c,1,sale,false);}catch(IllegalArgumentException e){duplicate=true;}check(duplicate,"repeated sale collection cannot duplicate Kinah");
			check(value(c,"SELECT COUNT(*) n FROM inventory WHERE item_owner=2 AND enchant=15 AND tempering=5 AND item_color=11259375 AND item_creator='Custody check' AND item_skin=110900001")==1,"split preserves enhancement, tempering, skin, dye and creator");
			check(value(c,"SELECT COUNT(*) n FROM item_stones s JOIN inventory i USING(item_unique_id) WHERE i.item_owner=2 AND s.item_id=167000001")==1,"split preserves socket records transactionally");
			CANCEL.invoke(null,c,1,sale);c.commit();check(value(c,"SELECT COUNT(*) n FROM central_market_stock WHERE account_id=1 AND order_id IS NULL")==1,"cancel returns only the unsold remainder");
			catalog(c,"refund",1000);long unfilled=order(c,3,"refund","B",110,4);exec(c,"UPDATE central_market_wallet SET kinah=kinah-440 WHERE account_id=3");CANCEL.invoke(null,c,3,unfilled);c.commit();check(value(c,"SELECT kinah n FROM central_market_wallet WHERE account_id=3")==5000,"cancel refunds reserved Kinah");
			boolean rejected=false;try{CANCEL.invoke(null,c,3,unfilled);}catch(InvocationTargetException e){rejected=true;}check(rejected,"second cancellation cannot issue another refund");
			catalog(c,"priority",1000);long low=order(c,2,"priority","B",100,1),high=order(c,3,"priority","B",110,1);long prioritySale=order(c,1,"priority","S",100,1);stock(c,1,"priority",prioritySale,1);match(c,"priority",new ArrayList<>());c.commit();
			check(value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",high)==0&&value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",low)==1,"highest-price preorder fills first");
			catalog(c,"fifo",1000);long first=order(c,4,"fifo","B",100,1),second=order(c,5,"fifo","B",100,1);long fifoSale=order(c,1,"fifo","S",100,1);stock(c,1,"fifo",fifoSale,1);match(c,"fifo",new ArrayList<>());c.commit();check(value(c,"SELECT SUM(remaining) n FROM central_market_orders WHERE id IN (?,?)",first,second)==1,"equal highest-price preorders randomly fill exactly one eligible buyer");
			catalog(c,"self",1000);order(c,1,"self","B",100,1);long selfSale=order(c,1,"self","S",100,1);stock(c,1,"self",selfSale,1);match(c,"self",new ArrayList<>());c.commit();check(value(c,"SELECT COUNT(*) n FROM central_market_trades WHERE variant='self'")==0,"same account cannot trade with itself");
			catalog(c,"ceiling",100);order(c,6,"ceiling","B",100,1);order(c,7,"ceiling","B",100,1);long ceilingSale=order(c,1,"ceiling","S",100,1);stock(c,1,"ceiling",ceilingSale,1);match(c,"ceiling",new ArrayList<>());c.commit();check(value(c,"SELECT COUNT(*) n FROM central_market_trades WHERE variant='ceiling' AND buyer_account IN (6,7)")==1,"ceiling lottery fills exactly one eligible account");
			catalog(c,"rollback",1000);long rollbackBuy=order(c,2,"rollback","B",100,1),rollbackSale=order(c,1,"rollback","S",100,2);stock(c,1,"rollback",rollbackSale,2);c.commit();long trades=value(c,"SELECT COUNT(*) n FROM central_market_trades");ids=new ArrayList<>();match(c,"rollback",ids);c.rollback();for(int id:ids)IDFactory.getInstance().releaseId(id);
			check(value(c,"SELECT COUNT(*) n FROM central_market_trades")==trades&&value(c,"SELECT remaining n FROM central_market_orders WHERE id=?",rollbackBuy)==1,"rollback restores orders and ledger together");
			check(value(c,"SELECT SUM(i.item_count) n FROM central_market_stock s JOIN inventory i USING(item_unique_id) WHERE s.order_id=?",rollbackSale)==2,"rollback restores item custody and quantity");
			check(CentralMarketRules.volume("Weapons")==100 && CentralMarketRules.volume("Accessories")==50 && CentralMarketRules.volume("Materials")==1 && CentralMarketRules.volume("Other")==3,"published warehouse volume categories");
			check(CentralMarketRules.collect(1000,false)==650&&CentralMarketRules.collect(1000,true)==845,"base and Premium collection returns");
			check(CentralMarketRules.collect(CentralMarketRules.MAX_KINAH,true)==844999999999999L,"tax rounding remains exact at currency limit");
			check(CentralMarketRules.ladder(1000,1,10000).getFirst()==925&&CentralMarketRules.ladder(1000,1,10000).getLast()==1075,"price band is plus or minus 7.5 percent");
			check(CentralMarketRules.adjust(1000,500,1005,10,0)==1005,"demand cannot exceed absolute cap");
			boolean overflow=false;try{CentralMarketRules.total(Long.MAX_VALUE,2);}catch(IllegalArgumentException e){overflow=true;}check(overflow,"overflow is refused");
			virtualOrders(c);
			c.commit();
		}
		try(Connection c=DatabaseFactory.getConnection()){check(value(c,"SELECT COUNT(*) n FROM central_market_trades")>0,"committed trades survive a new database connection");}
		Files.createDirectories(Path.of("game-server/target/central-market-validation"));Files.writeString(Path.of("game-server/target/central-market-validation/database-results.txt"),"PASS: "+assertions+" checks in "+schema+". No live character data copied or changed.\n");
		System.out.println("PASS: "+assertions+" checks; isolated database "+schema);System.exit(0);
	}
}
