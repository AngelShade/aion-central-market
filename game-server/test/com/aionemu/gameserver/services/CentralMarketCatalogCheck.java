package com.aionemu.gameserver.services;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import com.aionemu.gameserver.configs.main.GSConfig;
import com.aionemu.gameserver.model.templates.item.ExtraInventory;
import com.aionemu.gameserver.model.templates.item.ItemTemplate;
import com.aionemu.gameserver.model.templates.item.enums.ItemGroup;

/** Read-only catalog regression using real item metadata and client icon mappings. */
public final class CentralMarketCatalogCheck {
    private static void set(Object object, String name, Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static int number(Attributes a,String name) {String v=a.getValue(name);return v==null?0:Integer.parseInt(v);}
    public static void main(String[] args) throws Exception {
        GSConfig.PLAYER_MAX_LEVEL=65;
        CentralMarketHttpService.loadIcons();
        Set<Integer> eligible=new HashSet<>();int[] special={0};
        SAXParserFactory.newInstance().newSAXParser().parse(Path.of(args[0]).toFile(),new DefaultHandler(){
            ItemTemplate current;
            @Override public void startElement(String uri,String local,String name,Attributes a) {
                try {
                    if(name.equals("item_template")) {
                        current=new ItemTemplate();set(current,"itemId",number(a,"id"));set(current,"name",a.getValue("name"));
                        set(current,"mask",number(a,"mask"));set(current,"price",number(a,"price"));set(current,"level",number(a,"level"));
                        set(current,"expireTime",number(a,"expire_time"));
                        String group=a.getValue("item_group");if(group!=null)set(current,"itemGroup",ItemGroup.valueOf(group));
                    } else if(name.equals("inventory") && current!=null) {
                        ExtraInventory extra=new ExtraInventory();set(extra,"id",number(a,"id"));set(current,"extraInventory",extra);
                    }
                }catch(Exception e){throw new IllegalStateException(e);}
            }
            @Override public void endElement(String uri,String local,String name) {
                if(!name.equals("item_template"))return;
                if(CentralMarketService.eligible(current)) {
                    if(current.getExtraInventoryId()>=0 || current.getItemGroup()==ItemGroup.QUEST)throw new AssertionError("Quest or special inventory item entered market");
                    eligible.add(current.getTemplateId());
                } else if(current.getExtraInventoryId()>=0) special[0]++;
                current=null;
            }
        });
        if(eligible.size()<1000)throw new AssertionError("Incomplete catalog: "+eligible.size());
        if(!eligible.contains(100000096))throw new AssertionError("Akarios Sword missing from catalog");
        if(special[0]==0)throw new AssertionError("No special inventory exclusions checked");
        System.out.println("PASS: "+eligible.size()+" eligible real templates; "+special[0]+" special-inventory templates excluded.");
    }
}
