package com.trading.central.kafka;

import com.trading.central.model.OrderReportMsg;
import com.trading.central.model.StockQuoteMsg;
import com.trading.central.model.TradeReportMsg;
import com.trading.central.util.Constants.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class KafkaProducerService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public KafkaProducerService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    private void sendMessage(String topic, String key, Object value) {
        if (!kafkaEnabled) {
            log.warn("Kafka 已禁用，消息未发送: topic={} key={}", topic, key);
            return;
        }
        try {
            kafkaTemplate.send(topic, key, value);
            log.debug("[Kafka 发送] topic={} key={}", topic, key);
        } catch (Exception e) {
            log.error("[Kafka 发送失败] topic={}", topic, e);
        }
    }

    public void sendOrderReport(String orderId, String status, String reason) {
        OrderReportMsg msg = new OrderReportMsg();
        msg.setOrderId(orderId);
        msg.setStatus(status);
        msg.setReason(reason != null ? reason : "");
        msg.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        sendMessage(Topics.ORDER_REPORT, orderId, msg);
    }

    public void sendTradeReport(TradeReportMsg msg) {
        if (msg.getTradeTime() == null) {
            msg.setTradeTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        // 发送到交易客户端
        sendMessage(Topics.TRADE_REPORT, msg.getBuyerOrderId(), msg);
        // 发送到网上信息发布系统
        sendMessage(Topics.WEB_TRADE_REPORT, msg.getStockCode(), msg);
    }

    public void sendStockQuote(StockQuoteMsg msg) {
        if (msg.getQuoteTime() == null) {
            msg.setQuoteTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        if (msg.getTradeStatus() == null) {
            msg.setTradeStatus("可交易");
        }
        if (msg.getNotice() == null) {
            msg.setNotice("");
        }
        if (msg.getStockName() == null) {
            msg.setStockName("");
        }
        sendMessage(Topics.STOCK_QUOTE, msg.getStockCode(), msg);
    }
}
