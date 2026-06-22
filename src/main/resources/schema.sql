-- ============================================================
-- 中央交易系统数据库
-- Central Trading System Database Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS central_trading
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE central_trading;

-- ------------------------------------------------------------
-- 股票基础信息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_info (
  stock_code       CHAR(6)        PRIMARY KEY,
  stock_name       VARCHAR(64)    NOT NULL,
  stock_type       VARCHAR(10)    NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL=普通股, ST=ST股票',
  previous_close   DECIMAL(18,2)  NOT NULL DEFAULT 0.00    COMMENT '昨日收盘价',
  latest_price     DECIMAL(18,2)  NOT NULL DEFAULT 0.00    COMMENT '最新成交价',
  open_price       DECIMAL(18,2)  NOT NULL DEFAULT 0.00    COMMENT '今日开盘价',
  trade_status     VARCHAR(20)    NOT NULL DEFAULT 'TRADING' COMMENT 'TRADING=可交易, SUSPENDED=停牌',
  notice           VARCHAR(512)   NULL                      COMMENT '股票公告',
  update_time      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_stock_type CHECK (stock_type IN ('NORMAL', 'ST')),
  CONSTRAINT chk_trade_status CHECK (trade_status IN ('TRADING', 'SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 涨跌停配置表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS price_limit_config (
  stock_type   VARCHAR(10) PRIMARY KEY COMMENT 'NORMAL / ST',
  limit_rate   DECIMAL(5,4) NOT NULL   COMMENT '涨跌幅比例，如0.1000=10%'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO price_limit_config (stock_type, limit_rate) VALUES
  ('NORMAL', 0.1000),
  ('ST',     0.0500)
ON DUPLICATE KEY UPDATE limit_rate = VALUES(limit_rate);

-- ------------------------------------------------------------
-- 委托订单表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_book (
  order_id           VARCHAR(32)   PRIMARY KEY              COMMENT '委托编号（来自交易客户端）',
  account_id         VARCHAR(20)   NOT NULL                 COMMENT '资金账户ID',
  stock_code         CHAR(6)       NOT NULL,
  side               VARCHAR(4)    NOT NULL                 COMMENT 'BUY / SELL',
  price              DECIMAL(18,2) NOT NULL                 COMMENT '委托价格',
  quantity           INT           NOT NULL                 COMMENT '委托数量',
  filled_quantity    INT           NOT NULL DEFAULT 0       COMMENT '已成交数量',
  remaining_quantity INT           NOT NULL                 COMMENT '剩余未成交数量',
  status             VARCHAR(20)   NOT NULL DEFAULT 'ACCEPTED' COMMENT 'SUBMITTED/ACCEPTED/PART_TRADED/TRADED/CANCELED/EXPIRED/REJECTED',
  reject_reason      VARCHAR(256)  NULL,
  entry_time         DATETIME(3)   NOT NULL                 COMMENT '进入系统时间（精确到毫秒）',
  update_time        DATETIME(3)   NOT NULL                 COMMENT '最后更新时间',
  trade_date         DATE          NOT NULL                 COMMENT '交易日期',
  INDEX idx_order_stock_side (stock_code, side, status),
  INDEX idx_order_account (account_id),
  INDEX idx_order_trade_date (trade_date, status),
  CONSTRAINT chk_order_side CHECK (side IN ('BUY', 'SELL')),
  CONSTRAINT chk_order_price CHECK (price > 0),
  CONSTRAINT chk_order_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 成交记录表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_record (
  trade_no         VARCHAR(32)    PRIMARY KEY               COMMENT '成交编号',
  buyer_order_id   VARCHAR(32)    NOT NULL,
  seller_order_id  VARCHAR(32)    NOT NULL,
  stock_code       CHAR(6)        NOT NULL,
  trade_price      DECIMAL(18,2)  NOT NULL,
  trade_quantity   INT            NOT NULL,
  trade_amount     DECIMAL(18,2)  NOT NULL                  COMMENT '成交金额 = price * quantity',
  trade_time       DATETIME(3)    NOT NULL,
  INDEX idx_trade_stock_time (stock_code, trade_time),
  INDEX idx_trade_buyer (buyer_order_id),
  INDEX idx_trade_seller (seller_order_id),
  CONSTRAINT chk_trade_price CHECK (trade_price > 0),
  CONSTRAINT chk_trade_quantity CHECK (trade_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 成交价格历史（用于统计最高最低价）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_price_history (
  id            BIGINT         PRIMARY KEY AUTO_INCREMENT,
  stock_code    CHAR(6)        NOT NULL,
  trade_price   DECIMAL(18,2)  NOT NULL,
  trade_time    DATETIME(3)    NOT NULL,
  INDEX idx_price_history_stock_time (stock_code, trade_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 初始股票数据（演示用）
-- ------------------------------------------------------------
INSERT INTO stock_info (stock_code, stock_name, stock_type, previous_close, latest_price, open_price, trade_status, notice) VALUES
  ('600519', '贵州茅台', 'NORMAL', 1400.00, 1400.00, 1400.00, 'TRADING', '年度股东大会公告已发布'),
  ('601398', '工商银行', 'NORMAL', 5.82,    5.82,    5.82,    'TRADING', ''),
  ('600036', '招商银行', 'NORMAL', 35.50,   35.50,   35.50,   'TRADING', ''),
  ('000001', '平安银行', 'NORMAL', 12.30,   12.30,   12.30,   'TRADING', ''),
  ('600000', '浦发银行', 'NORMAL', 7.85,    7.85,    7.85,    'TRADING', ''),
  ('601988', '中国银行', 'NORMAL', 4.15,    4.15,    4.15,    'TRADING', ''),
  ('600016', '民生银行', 'NORMAL', 3.92,    3.92,    3.92,    'TRADING', ''),
  ('000858', '五粮液',   'NORMAL', 168.50,  168.50,  168.50,  'TRADING', ''),
  ('600900', 'ST长江电力', 'ST',   25.60,   25.60,   25.60,   'TRADING', '风险警示'),
  ('000002', 'ST万科A',   'ST',    8.75,    8.75,    8.75,    'TRADING', '风险警示')
ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name);
