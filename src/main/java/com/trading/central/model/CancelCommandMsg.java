package com.trading.central.model;

import lombok.Data;

@Data
public class CancelCommandMsg {
    private String orderId;
    private String accountId;
    private String timestamp;
}
