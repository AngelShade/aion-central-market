package com.aionemu.gameserver.services;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.actions.*;
import static com.aionemu.gameserver.services.CentralMarketService.*;

/** Read-only item discovery. Classification never changes custody, volume or matching rules. */
final class CentralMarketBrowse {
    private static final List<String> CATEGORIES=List.of("Weapons","Armor","Accessories","Enhancement","Materials","Consumables","Stigmas","Recipes & Skills","Coins & Medals","Appearance","Pets & Mounts","Housing","Containers","Utility","Other");
    private record Entry(ItemTemplate item,String category,String type,String slot,String quality,String search) {}
    private record Index(List<ItemTemplate> source,List<Entry> entries,List<Map<String,Object>> tree) {}
    private static volatile Index cached;
    private CentralMarketBrowse() {}

    static String type(ItemTemplate t) {
        if(t.isStigma()) return "Stigma Stones";
        String g=t.getItemGroup().name();
        if(g.startsWith("RB_")) return "Cloth Armor";
        if(g.startsWith("LT_")) return "Leather Armor";
        if(g.startsWith("CH_")) return "Chain Armor";
        if(g.startsWith("PL_")) return "Plate Armor";
        if(g.startsWith("CL_") && !g.equals("CL_SHIELD")) return "Costumes";
        return switch(g) {
            case "GUN" -> "Pistols"; case "CANNON" -> "Aethercannons"; case "KEYBLADE" -> "Aether Keys";
            case "SWORD" -> "Swords"; case "GREATSWORD" -> "Greatswords"; case "DAGGER" -> "Daggers";
            case "MACE" -> "Maces"; case "ORB" -> "Orbs"; case "SPELLBOOK" -> "Spellbooks";
            case "POLEARM" -> "Polearms"; case "STAFF" -> "Staves"; case "BOW" -> "Bows"; case "HARP" -> "Harps";
            case "SHIELD","CL_SHIELD" -> "Shields"; case "HEAD" -> "Headwear";
            case "TORSO","PANTS","GLOVE","SHOULDER","SHOES" -> "Other Armor";
            case "RING" -> "Rings"; case "EARRING" -> "Earrings"; case "NECKLACE" -> "Necklaces";
            case "BELT" -> "Belts"; case "WING" -> "Wings"; case "PLUME" -> "Plumes";
            case "MANASTONE" -> "Manastones"; case "SPECIAL_MANASTONE" -> "Composite Manastones";
            case "ENCHANTMENT" -> "Enchantment Stones"; case "TAMPERING" -> "Tempering Solutions";
            case "GODSTONE" -> "Godstones"; case "PACK_SCROLL" -> "Wrapping Scrolls";
            case "FLUX" -> "Fluxes"; case "BALIC_MATERIAL" -> "Balaur Materials";
            case "RAWHIDE" -> "Rawhide"; case "SOULSTONE" -> "Soulstones";
            case "GATHERABLE","GATHERABLE_BONUS" -> "Gathered Materials"; case "DROP_MATERIAL" -> "Crafting Materials";
            case "STIGMA" -> "Stigma Stones"; case "STIGMA_SHARD" -> "Stigma Shards";
            case "RECIPE" -> "Crafting Designs"; case "SKILLBOOK" -> "Skill Books";
            case "COINS" -> "Coins"; case "MEDALS" -> "Medals";
            case "POWER_SHARDS" -> "Power Shards"; case "ARROW" -> "Arrows"; case "CRAFT_BOOST" -> "Crafting Boosts";
            default -> actionType(t);
        };
    }
    private static String actionType(ItemTemplate t) {
        // The matching 4.8 template's action and item family are authoritative, not translated names.
        if(t.getActions()!=null) {
            var a=t.getActions();
            if(a.getRideAction()!=null) return "Mounts";
            if(a.getAdoptPetAction()!=null) return "Pets";
            if(a.getHouseObjectAction()!=null || a.getDecorateAction()!=null) return "Furniture & Decorations";
            if(a.getCraftLearnAction()!=null) return "Crafting Designs";
            if(a.getDyeAction()!=null) return "Dyes";
            if(a.getTuningAction()!=null) return "Retuning Scrolls";
            if(a.getPolishAction()!=null) return "Idians";
            if(a.getEnchantAction()!=null) return t.getTemplateId()/1_000_000==167?"Manastones":"Enchantment Stones";
            for(var action:a.getItemActions()) {
                if(action instanceof DecomposeAction) return "Boxes & Bundles";
                if(action instanceof CosmeticItemAction) return "Appearance Changes";
                if(action instanceof EmotionLearnAction) return "Emote Cards";
                if(action instanceof AnimationAddAction) return "Motion Cards";
                if(action instanceof TitleAddAction) return "Title Cards";
                if(action instanceof SkillLearnAction) return "Skill Books";
                if(action instanceof InstanceTimeClear) return "Instance Entry Scrolls";
                if(action instanceof ExpandInventoryAction) return "Cube Expansion";
                if(action instanceof ChargeAction) return "Conditioning Items";
                if(action instanceof FireworksUseAction) return "Fireworks";
                if(action instanceof PackAction) return "Wrapping Scrolls";
                if(action instanceof AssemblyItemAction) return "Assembly Items";
            }
        }
        return switch(t.getTemplateId()/1_000_000) {
            case 160 -> "Food & Drink"; case 161 -> "Resurrection Items"; case 162 -> "Potions & Serums";
            case 164 -> "Scrolls & Transformation";
            default -> t.getActions()!=null && t.getActions().getSkillUseAction()!=null?"Other Consumables":"Miscellaneous";
        };
    }
    static String browseCategory(ItemTemplate t,String type) {
        if(t.isWeapon()) return "Weapons";
        if(Set.of("Rings","Earrings","Necklaces","Belts","Wings","Plumes").contains(type)) return "Accessories";
        if(t.isArmor()) return "Armor";
        if(Set.of("Manastones","Composite Manastones","Enchantment Stones","Tempering Solutions","Godstones","Wrapping Scrolls","Retuning Scrolls","Idians").contains(type)) return "Enhancement";
        if(Set.of("Fluxes","Balaur Materials","Rawhide","Soulstones","Gathered Materials","Crafting Materials").contains(type)) return "Materials";
        if(type.startsWith("Stigma")) return "Stigmas";
        if(Set.of("Crafting Designs","Skill Books").contains(type)) return "Recipes & Skills";
        if(Set.of("Coins","Medals").contains(type)) return "Coins & Medals";
        if(Set.of("Dyes","Appearance Changes","Emote Cards","Motion Cards","Title Cards").contains(type)) return "Appearance";
        if(Set.of("Pets","Mounts").contains(type)) return "Pets & Mounts";
        if(type.equals("Furniture & Decorations")) return "Housing";
        if(type.equals("Boxes & Bundles")) return "Containers";
        if(Set.of("Instance Entry Scrolls","Cube Expansion","Conditioning Items","Assembly Items").contains(type)) return "Utility";
        if(Set.of("Potions & Serums","Resurrection Items","Scrolls & Transformation","Food & Drink","Other Consumables","Power Shards","Arrows","Crafting Boosts","Fireworks").contains(type)) return "Consumables";
        return "Other";
    }
    static String slot(ItemTemplate t) {
        String g=t.getItemGroup().name();
        if(g.endsWith("HEADS")||g.equals("HEAD")) return "Head";
        if(g.endsWith("TORSO")||g.endsWith("MULTISLOT")) return "Chest";
        if(g.endsWith("PANTS")) return "Legs";
        if(g.endsWith("GLOVE")) return "Hands";
        if(g.endsWith("SHOULDER")) return "Shoulders";
        if(g.endsWith("SHOES")) return "Feet";
        return "Other";
    }
    private static Index index(List<ItemTemplate> source) {
        Index found=cached; if(found!=null && found.source()==source) return found;
        List<Entry> entries=new ArrayList<>();
        for(var t:source) {String type=type(t);entries.add(new Entry(t,browseCategory(t,type),type,slot(t),t.getItemQuality()==null?"COMMON":t.getItemQuality().name(),t.getName().toLowerCase(Locale.ROOT)));}
        List<Map<String,Object>> tree=new ArrayList<>();
        for(String category:CATEGORIES) {
            var counts=entries.stream().filter(e->e.category().equals(category)).collect(Collectors.groupingBy(Entry::type,TreeMap::new,Collectors.counting()));
            if(!counts.isEmpty()) tree.add(Map.of("name",category,"count",counts.values().stream().mapToLong(Long::longValue).sum(),"types",counts.entrySet().stream().map(e->Map.of("name",e.getKey(),"count",e.getValue())).toList()));
        }
        found=new Index(source,List.copyOf(entries),List.copyOf(tree));cached=found;return found;
    }
    static long bound(Map<String,String> args,String key,long defaultValue,long max) {
        String value=args.getOrDefault(key,"").trim();if(value.isEmpty()) return defaultValue;
        try {return Math.max(0,Math.min(max,Long.parseLong(value)));}catch(NumberFormatException ex){throw new IllegalArgumentException("Enter a valid number for "+key+".");}
    }
    static void view(Connection c,Player p,Map<String,String> args,List<Integer> favorites,Map<String,Object> result,List<ItemTemplate> source) throws SQLException {
        boolean standalone=c.getAutoCommit();int isolation=c.getTransactionIsolation();
        if(standalone) {c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);}
        try {viewSnapshot(c,p,args,favorites,result,source);}
        finally {if(standalone) {try {c.rollback();} finally {c.setAutoCommit(true);c.setTransactionIsolation(isolation);}}}
    }
    private static void viewSnapshot(Connection c,Player p,Map<String,String> args,List<Integer> favorites,Map<String,Object> result,List<ItemTemplate> source) throws SQLException {
        Index index=index(source);
        String q=args.getOrDefault("q","").trim().toLowerCase(Locale.ROOT),category=args.getOrDefault("category","All Items"),sub=args.getOrDefault("sub","All"),filter=args.getOrDefault("filter","all"),sort=args.getOrDefault("sort","name"),quality=args.getOrDefault("quality","all"),slot=args.getOrDefault("slot","All");
        long minLevel=bound(args,"minLevel",0,65),maxLevel=bound(args,"maxLevel",65,65),minPrice=bound(args,"minPrice",0,CentralMarketRules.MAX_KINAH),maxPrice=bound(args,"maxPrice",CentralMarketRules.MAX_KINAH,CentralMarketRules.MAX_KINAH);
        if(minLevel>maxLevel || minPrice>maxPrice) throw new IllegalArgumentException("Minimum must be less than or equal to maximum.");
        Set<Integer> saved=new HashSet<>(favorites);
        List<Entry> matches=index.entries().stream().filter(e->e.search().contains(q))
            .filter(e->category.equals("All Items")||e.category().equals(category))
            .filter(e->sub.equals("All")||e.type().equals(sub)||e.item().getItemGroup().name().equals(sub))
            .filter(e->slot.equals("All")||e.slot().equals(slot))
            .filter(e->quality.equals("all")||e.quality().equalsIgnoreCase(quality))
            .filter(e->e.item().getLevel()>=minLevel&&e.item().getLevel()<=maxLevel)
            .filter(e->!filter.equals("favorites")||saved.contains(e.item().getTemplateId()))
            .filter(e->!filter.equals("usable")||p!=null&&(e.item().getRace()==Race.PC_ALL||e.item().getRace()==p.getRace())).toList();
        boolean changed=filter.equals("changed"),priced=minPrice>0||maxPrice<CentralMarketRules.MAX_KINAH;
        Map<Integer,Map<String,Object>> prices=Map.of();
        if(changed||priced||!sort.equals("name")||filter.equals("stock")) {
            // Server-side aggregate without enormous repeated IN lists. Read-only, transaction-consistent.
            String condition=changed?" AND c.base_price<>c.previous_price AND c.updated_at>=?":"";
            boolean activity=filter.equals("stock")||sort.equals("stock")||sort.equals("traded");
            List<Map<String,Object>> rows=changed?rows(c,priceSql(condition,activity),System.currentTimeMillis()-86_400_000L):rows(c,priceSql(condition,activity));
            prices=rows.stream().collect(Collectors.toMap(r->(int)lng(r,"item_id"),r->r));
            final var quotes=prices;
            matches=matches.stream().filter(e->quotes.containsKey(e.item().getTemplateId()))
                .filter(e->!filter.equals("stock")||lng(quotes.get(e.item().getTemplateId()),"stock")>0)
                .filter(e->!priced||lng(quotes.get(e.item().getTemplateId()),"base_price")>=minPrice&&lng(quotes.get(e.item().getTemplateId()),"base_price")<=maxPrice).toList();
            Comparator<Entry> compare=switch(sort) {
                case "price" -> Comparator.comparingLong(e->lng(quotes.get(e.item().getTemplateId()),"base_price"));
                case "price-high" -> Comparator.<Entry>comparingLong(e->lng(quotes.get(e.item().getTemplateId()),"base_price")).reversed();
                case "stock" -> Comparator.<Entry>comparingLong(e->lng(quotes.get(e.item().getTemplateId()),"stock")).reversed();
                case "traded" -> Comparator.<Entry>comparingLong(e->lng(quotes.get(e.item().getTemplateId()),"traded")).reversed();
                case "change" -> Comparator.<Entry>comparingDouble(e->movement(quotes.get(e.item().getTemplateId()))).reversed();
                default -> Comparator.comparing(e->e.item().getName());
            };
            matches=matches.stream().sorted(compare.thenComparing(e->e.item().getName()).thenComparingInt(e->e.item().getTemplateId())).toList();
        }
        int page=(int)bound(args,"page",1,Integer.MAX_VALUE);page=Math.max(1,Math.min(page,Math.max(1,(matches.size()+23)/24)));
        var slice=matches.subList((page-1)*24,Math.min(page*24,matches.size()));
        // Only the visible page needs order/trade aggregates for name, price and movement sorting.
        Map<Integer,Map<String,Object>> pagePrices=slice.isEmpty()?Map.of():catalogPrices(c,slice.stream().map(Entry::item).toList());
        List<Map<String,Object>> catalog=new ArrayList<>();
        for(var e:slice) {var row=templateView(e.item());row.put("type",e.type());row.put("browseCategory",e.category());var quote=prices.get(e.item().getTemplateId());if(quote!=null)row.putAll(quote);quote=pagePrices.get(e.item().getTemplateId());if(quote!=null)row.putAll(quote);catalog.add(row);}
        result.put("catalog",catalog);result.put("page",page);result.put("total",matches.size());result.put("categoryTree",index.tree());
        result.put("subcategories",index.tree().stream().filter(r->r.get("name").equals(category)).flatMap(r->((List<Map<String,Object>>)r.get("types")).stream()).map(r->r.get("name")).toList());
    }
    private static double movement(Map<String,Object> quote) {long previous=lng(quote,"previous_price");return previous<=0?0:Math.abs((double)lng(quote,"base_price")-previous)/previous;}
    private static String priceSql(String condition,boolean activity) {
        if(!activity) return "SELECT c.item_id,c.base_price,c.previous_price,c.updated_at FROM central_market_catalog c WHERE c.variant=CONCAT(c.item_id,':0:0')"+condition;
        return "SELECT c.item_id,c.base_price,c.previous_price,c.updated_at,COALESCE(t.traded,0) traded,COALESCE(s.stock,0) stock FROM central_market_catalog c LEFT JOIN (SELECT item_id,SUM(traded) traded FROM central_market_catalog GROUP BY item_id) t ON t.item_id=c.item_id LEFT JOIN (SELECT v.item_id,SUM(o.remaining) stock FROM central_market_orders o JOIN central_market_catalog v ON v.variant=o.variant WHERE o.side='S' AND o.state='OPEN' GROUP BY v.item_id) s ON s.item_id=c.item_id WHERE c.variant=CONCAT(c.item_id,':0:0')"+condition;
    }
}
