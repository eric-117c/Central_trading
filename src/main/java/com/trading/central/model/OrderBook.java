package com.trading.central.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderBook {
    private String orderId;
    private String accountId;
    private String stockCode;
    private String side;
    private BigDecimal price;
    private Integer quantity;
    private Integer filledQuantity;
    private Integer remainingQuantity;
    private String status;
    private String rejectReason;
    private LocalDateTime entryTime;
    private LocalDateTime updateTime;
    private java.time.LocalDate tradeDate;
}
