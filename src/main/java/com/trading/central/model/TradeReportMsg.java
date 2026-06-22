package com.trading.central.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class TradeReportMsg {
    private String tradeNo;
    private String buyerOrderId;
    private String sellerOrderId;
    private String stockCode;
    private BigDecimal tradePrice;
    private Integer tradeQuantity;
    private String tradeTime;

    private String buyerName;
    private String sellerName;
}