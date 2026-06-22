package com.trading.central.dashboard;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradingEvent {
    private String timestamp;
    private String type;
    private String label;
    private String stockCode;
    private String orderId;
    private String accountId;
    private String detail;
    private String side;
    private String price;
    private String quantity;
    private String buyerAccountId;
    private String sellerAccountId;
}
