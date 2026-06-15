package com.trading.central.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class StockInfo {
    private String stockCode;
    private String stockName;
    private String stockType;
    private BigDecimal previousClose;
    private BigDecimal latestPrice;
    private BigDecimal openPrice;
    private String tradeStatus;
    private String notice;
    private LocalDateTime updateTime;
}
