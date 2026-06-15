package com.trading.central.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderEntry {
    private String orderId;
    private String accountId;
    private String stockCode;
    private String side; // BUY or SELL
    private BigDecimal price;
    private Integer quantity;
    private Integer filledQuantity;
    private Integer remainingQuantity;
    private String status;
    private LocalDateTime entryTime;
}
