package com.trading.central.util;

import java.math.BigDecimal;

public class Constants {

    public static final class Topics {
        public static final String ORDER_COMMAND = "central.order.command";
        public static final String CANCEL_COMMAND = "central.cancel.command";
        public static final String STOCK_QUERY = "central.stock.query";
        
        public static final String STOCK_QUOTE = "client.stock.quote";
        public static final String TRADE_REPORT = "client.trade.report";
        public static final String ORDER_REPORT = "client.order.report";
        
        public static final String WEB_TRADE_REPORT = "webinfo.trade.report";
    }

    public enum OrderStatus {
        SUBMITTED,
        ACCEPTED,
        PART_TRADED,
        TRADED,
        CANCELED,
        EXPIRED,
        REJECTED
    }

    public enum Side {
        BUY,
        SELL
    }

    public enum StockType {
        NORMAL,
        ST
    }

    public enum TradeStatus {
        TRADING,
        SUSPENDED
    }

    public static final class DefaultLimitRates {
        public static final BigDecimal NORMAL = new BigDecimal("0.10");
        public static final BigDecimal ST = new BigDecimal("0.05");
    }
}
