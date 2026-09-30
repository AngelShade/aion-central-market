import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Create Central Market tables in the configured GameServer database. Never changes player rows. */
public class CentralMarketSchemaInstaller {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Arguments: GameServer directory, schema.sql");
        Path root = Path.of(args[0]).toAbsolutePath();
        Properties config = new Properties();
        try (var in = Files.newInputStream(root.resolve("config/network/database.properties"))) { config.load(in); }
        Path overrides = root.resolve("config/mygs.properties");
        if (Files.isRegularFile(overrides)) try (var in = Files.newInputStream(overrides)) { config.load(in); }
        String url = config.getProperty("database.url").replace("${gameserver.timezone}", config.getProperty("gameserver.timezone", "UTC"));
        try (Connection c = DriverManager.getConnection(url, config.getProperty("database.user"), config.getProperty("database.password")); Statement s = c.createStatement()) {
            for (String sql : Files.readString(Path.of(args[1])).split(";")) if (!sql.isBlank()) s.execute(sql);
            int tables = 0;
            try (ResultSet r = s.executeQuery("SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND (TABLE_NAME IN ('inventory','item_stones') OR TABLE_NAME LIKE 'central_market_%') ORDER BY TABLE_NAME")) {
                while (r.next()) {
                    String table = r.getString(1);
                    if (!"InnoDB".equalsIgnoreCase(r.getString(2))) throw new SQLException("Transactional storage required: " + table);
                    if (table.startsWith("central_market_")) { tables++; System.out.println(table + " : InnoDB"); }
                }
            }
            if (tables != 9) throw new SQLException("Expected 9 Central Market tables, found " + tables);
            System.out.println("Verified " + tables + " Central Market tables in " + c.getCatalog() + ". No player rows changed.");
        }
    }
}
