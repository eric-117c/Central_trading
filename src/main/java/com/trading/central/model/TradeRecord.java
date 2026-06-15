package com.trading.central.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TradeRecord {
    private String tradeNo;
    private String buyerOrderId;
    private String sellerOrderId;
    private String stockCode;
    private BigDecimal tradePrice;
    private Integer tradeQuantity;
    private BigDecimal tradeAmount;
    private LocalDateTime tradeTime;
}
