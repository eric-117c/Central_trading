package com.trading.central.model;

import lombok.Data;

@Data
public class StockQueryMsg {
    private String stockCode;
    private String queryId;
    private String timestamp;
}
