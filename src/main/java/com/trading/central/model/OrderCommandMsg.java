package com.trading.central.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderCommandMsg {
    private String accountId;
    private String orderId;
    private String stockCode;
    private String side;
    private BigDecimal price;
    private Integer quantity;
    private String timestamp;
}
