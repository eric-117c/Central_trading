package com.trading.central.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class StockQuoteMsg {
    private String stockCode;
    private String stockName;
    private BigDecimal latestPrice;
    private BigDecimal previousClose;
    private BigDecimal highestPrice;
    private BigDecimal lowestPrice;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private String tradeStatus;
    private String notice;
    private String quoteTime;
}
