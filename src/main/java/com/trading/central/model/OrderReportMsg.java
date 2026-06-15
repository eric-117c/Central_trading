package com.trading.central.model;

import lombok.Data;

@Data
public class OrderReportMsg {
    private String orderId;
    private String status;
    private String reason;
    private String timestamp;
}
