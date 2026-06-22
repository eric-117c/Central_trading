package com.trading.central.dashboard;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class TradingEventBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public TradingEventBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcast(TradingEvent event) {
        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
        }
        messagingTemplate.convertAndSend("/topic/trading-log", event);
    }

    public void order(String stockCode, String orderId, String accountId, String detail) {
        broadcast(TradingEvent.builder()
                .type("ORDER").label("下单")
                .stockCode(stockCode).orderId(orderId).accountId(accountId).detail(detail)
                .build());
    }

    public void order(String stockCode, String orderId, String accountId, String side, String price, String quantity, String detail) {
        broadcast(TradingEvent.builder()
                .type("ORDER").label("下单")
                .stockCode(stockCode).orderId(orderId).accountId(accountId)
                .side(side).price(price).quantity(quantity).detail(detail)
                .build());
    }

    public void match(String stockCode, String detail) {
        broadcast(TradingEvent.builder()
                .type("MATCH").label("撮合")
                .stockCode(stockCode).detail(detail)
                .build());
    }

    public void match(String stockCode, String price, String quantity, String buyerAccountId, String sellerAccountId, String detail) {
        broadcast(TradingEvent.builder()
                .type("MATCH").label("撮合")
                .stockCode(stockCode).price(price).quantity(quantity)
                .buyerAccountId(buyerAccountId).sellerAccountId(sellerAccountId).detail(detail)
                .build());
    }

    public void cancel(String stockCode, String orderId, String accountId, String detail) {
        broadcast(TradingEvent.builder()
                .type("CANCEL").label("撤单")
                .stockCode(stockCode).orderId(orderId).accountId(accountId).detail(detail)
                .build());
    }

    public void quote(String stockCode, String detail) {
        broadcast(TradingEvent.builder()
                .type("QUOTE").label("行情")
                .stockCode(stockCode).detail(detail)
                .build());
    }

    public void error(String detail) {
        broadcast(TradingEvent.builder()
                .type("ERROR").label("错误")
                .detail(detail)
                .build());
    }

    public void system(String detail) {
        broadcast(TradingEvent.builder()
                .type("SYSTEM").label("系统")
                .detail(detail)
                .build());
    }
}
