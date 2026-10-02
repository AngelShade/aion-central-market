import java.nio.file.*;
import java.sql.*;
import java.util.Properties;

/** Read-only live quote counts. Does not read wallets, characters or account credentials into output. */
public class CentralMarketSimulationStatus {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();Properties p=new Properties();
        try(var input=Files.newInputStream(root.resolve("config/network/database.properties"))){p.load(input);}
        Path overrides=root.resolve("config/mygs.properties");
        if(Files.exists(overrides))try(var input=Files.newInputStream(overrides)){p.load(input);}
        String url=p.getProperty("database.url").replace("${gameserver.timezone}","UTC");
        try(Connection c=DriverManager.getConnection(url,p.getProperty("database.user"),p.getProperty("database.password"))){
            c.setReadOnly(true);
            for(String sql:new String[]{
                "SELECT COUNT(*) catalog_variants FROM central_market_catalog",
                "SELECT COUNT(*) initialized_templates FROM central_market_simulation",
                "SELECT COUNT(*) initialized_plain_templates FROM central_market_simulation s JOIN central_market_catalog c ON c.variant=s.variant WHERE c.variant=CONCAT(c.item_id,':0:0')",
                "SELECT side,COUNT(*) quotes,SUM(remaining) quantity FROM central_market_orders WHERE account_id=0 AND state='OPEN' GROUP BY side",
                "SELECT COUNT(*) simulated_real_trades FROM central_market_trades WHERE buyer_account=0 OR seller_account=0",
                "SELECT COUNT(*) settlement_wallet_mismatches FROM central_market_wallet w LEFT JOIN (SELECT o.account_id,SUM(z.gross) gross FROM central_market_settlements z JOIN central_market_orders o ON o.id=z.order_id WHERE o.side='S' GROUP BY o.account_id) x ON x.account_id=w.account_id WHERE w.proceeds<>COALESCE(x.gross,0)",
                "SELECT COUNT(*) purchase_custody_mismatches FROM central_market_orders o JOIN central_market_settlements z ON z.order_id=o.id LEFT JOIN (SELECT s.order_id,SUM(i.item_count) quantity FROM central_market_stock s JOIN inventory i USING(item_unique_id) GROUP BY s.order_id) x ON x.order_id=o.id WHERE o.side='B' AND o.account_id>0 AND o.quantity-o.remaining-z.collected_quantity<>COALESCE(x.quantity,0)"
            }) try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){
                var metadata=r.getMetaData();
                while(r.next()){
                    StringBuilder line=new StringBuilder();
                    for(int i=1;i<=metadata.getColumnCount();i++){if(i>1)line.append(", ");line.append(metadata.getColumnLabel(i)).append('=').append(r.getString(i));}
                    System.out.println(line);
                }
            }
        }
    }
}
