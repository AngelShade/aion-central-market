-- Central Market uses the existing inventory row to preserve every Aion item attribute.
-- Location 125 is account-owned market custody. central_market_stock controls availability.
CREATE TABLE IF NOT EXISTS central_market_wallet (
 account_id INT NOT NULL PRIMARY KEY, kinah BIGINT NOT NULL DEFAULT 0,
 proceeds BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_catalog (
 variant VARCHAR(96) NOT NULL PRIMARY KEY, item_id INT NOT NULL, enchant INT NOT NULL,
 tempering INT NOT NULL, base_price BIGINT NOT NULL, floor_price BIGINT NOT NULL,
 ceiling_price BIGINT NOT NULL, traded BIGINT NOT NULL DEFAULT 0,
 updated_at BIGINT NOT NULL DEFAULT 0, previous_price BIGINT NOT NULL, attributes_json TEXT NULL,
 INDEX item_lookup (item_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_orders (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account_id INT NOT NULL,
 variant VARCHAR(96) NOT NULL, side CHAR(1) NOT NULL, price BIGINT NOT NULL,
 quantity BIGINT NOT NULL, remaining BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
 created_at BIGINT NOT NULL, available_at BIGINT NOT NULL,
 INDEX matching (variant,side,state,price,created_at), INDEX owned (account_id,state)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_stock (
 item_unique_id INT NOT NULL PRIMARY KEY, account_id INT NOT NULL,
 variant VARCHAR(96) NOT NULL, order_id BIGINT NULL,
 INDEX owned (account_id,order_id), INDEX listing (order_id),
 CONSTRAINT cm_stock_order FOREIGN KEY (order_id) REFERENCES central_market_orders(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_trades (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, variant VARCHAR(96) NOT NULL,
 buyer_account INT NOT NULL, seller_account INT NOT NULL, quantity BIGINT NOT NULL,
 unit_price BIGINT NOT NULL, buy_order BIGINT NOT NULL, sell_order BIGINT NOT NULL,
 traded_at BIGINT NOT NULL, INDEX chart (variant,traded_at),
 INDEX buyer_history (buyer_account,traded_at), INDEX seller_history (seller_account,traded_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_favorites (
 account_id INT NOT NULL, item_id INT NOT NULL, PRIMARY KEY (account_id,item_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_requests (
 request_id VARCHAR(64) NOT NULL PRIMARY KEY, account_id INT NOT NULL,
 result VARCHAR(512) NOT NULL, created_at BIGINT NOT NULL,
 INDEX receipt_expiry (created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_collections (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account_id INT NOT NULL,
 gross BIGINT NOT NULL, net BIGINT NOT NULL, collected_at BIGINT NOT NULL,
 INDEX account_history (account_id,collected_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS central_market_searches (
 account_id INT NOT NULL, term VARCHAR(60) NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY (account_id,term), INDEX recent_search (account_id,created_at)
) ENGINE=InnoDB;
