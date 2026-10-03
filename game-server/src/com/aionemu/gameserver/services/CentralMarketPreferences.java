package com.aionemu.gameserver.services;

import java.sql.*;
import java.util.*;
import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.world.World;
import static com.aionemu.gameserver.services.CentralMarketService.*;

/** Account preferences and preview eligibility; never moves items or places orders. */
final class CentralMarketPreferences {
    private CentralMarketPreferences() {}
    static boolean load(Connection c,int account)throws SQLException {
        var saved=row(c,"SELECT always_max FROM central_market_preferences WHERE account_id=?",account);
        return saved!=null&&lng(saved,"always_max")!=0;
    }
    static void save(Connection c,int account,boolean enabled)throws SQLException {
        update(c,"INSERT INTO central_market_preferences(account_id,always_max) VALUES(?,?) ON DUPLICATE KEY UPDATE always_max=VALUES(always_max)",account,enabled?1:0);
    }
    public static String tryAction(Player player,Map<String,String> args,String requestId)throws Exception {
        if(!"preference".equals(args.get("action")))return null;
        String value=args.getOrDefault("alwaysMax","");
        if(!value.equals("0")&&!value.equals("1"))throw new IllegalArgumentException("Invalid Always Max setting.");
        Object guard=player.getClientConnection();if(guard==null)throw new IllegalArgumentException("Log in to save market settings.");
        synchronized(guard) {
            if(World.getInstance().getPlayer(player.getObjectId())!=player||GameServer.isShuttingDownSoon())throw new IllegalArgumentException("Reopen Market after login to save settings.");
            int account=player.getAccount().getId();
            try(Connection c=DatabaseFactory.getConnection()) {
                c.setAutoCommit(false);
                try {
                    update(c,"INSERT IGNORE INTO central_market_preferences(account_id,always_max) VALUES(?,0)",account);
                    row(c,"SELECT account_id FROM central_market_preferences WHERE account_id=? FOR UPDATE",account);
                    var prior=row(c,"SELECT result FROM central_market_requests WHERE request_id=? AND account_id=?",requestId,account);
                    if(prior!=null){c.rollback();return str(prior,"result");}
                    save(c,account,value.equals("1"));String result="Always Max "+(value.equals("1")?"enabled":"disabled")+". Saved for this account.";
                    update(c,"INSERT INTO central_market_requests VALUES(?,?,?,?)",requestId,account,result,System.currentTimeMillis());c.commit();return result;
                } catch(Exception e){c.rollback();throw e;}
            }
        }
    }
    public static void decorateSnapshot(Map<String,Object> result,Player player)throws SQLException {
        if(!result.containsKey("storages"))return;
        try(Connection c=DatabaseFactory.getConnection()){result.put("alwaysMax",load(c,player.getAccount().getId()));}
    }
    static boolean marketTransferable(Item item) {
        return !item.isEquipped()&&item.isTradeable()&&item.getExpireTime()==0&&item.getPendingTuneResult()==null&&eligible(item.getItemTemplate());
    }
    public static void decorateItem(Map<String,Object> result,Item item,int source) {
        result.put("marketTransferable",source!=125&&marketTransferable(item));
    }
}
