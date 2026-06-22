package com.trading.central.kafka;

import com.trading.central.dashboard.TradingEventBroadcaster;
import com.trading.central.model.CancelCommandMsg;
import com.trading.central.model.OrderCommandMsg;
import com.trading.central.model.StockQueryMsg;
import com.trading.central.service.OrderService;
import com.trading.central.util.Constants.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaConsumerService {

    private final OrderService orderService;
    private final TradingEventBroadcaster broadcaster;

    public KafkaConsumerService(OrderService orderService, TradingEventBroadcaster broadcaster) {
        this.orderService = orderService;
        this.broadcaster = broadcaster;
    }

    @KafkaListener(topics = Topics.ORDER_COMMAND, autoStartup = "${app.kafka.enabled:false}")
    public void onOrderCommand(OrderCommandMsg msg) {
        try {
            orderService.receiveOrder(msg);
        } catch (Exception e) {
            log.error("[Kafka] 处理委托消息异常: {}", msg, e);
            broadcaster.error("处理委托消息异常: " + e.getMessage());
        }
    }

    @KafkaListener(topics = Topics.CANCEL_COMMAND, autoStartup = "${app.kafka.enabled:false}")
    public void onCancelCommand(CancelCommandMsg msg) {
        try {
            broadcaster.cancel(null, msg.getOrderId(), msg.getAccountId(), "收到撤单请求 " + msg.getOrderId());
            orderService.cancelOrder(msg);
        } catch (Exception e) {
            log.error("[Kafka] 处理撤单消息异常: {}", msg, e);
            broadcaster.error("处理撤单消息异常: " + e.getMessage());
        }
    }

    @KafkaListener(topics = Topics.STOCK_QUERY, autoStartup = "${app.kafka.enabled:false}")
    public void onStockQuery(StockQueryMsg msg) {
        try {
            broadcaster.quote(msg.getStockCode(), "收到行情查询 " + msg.getStockCode());
            orderService.handleStockQuery(msg);
        } catch (Exception e) {
            log.error("[Kafka] 处理行情查询消息异常: {}", msg, e);
            broadcaster.error("处理行情查询异常: " + e.getMessage());
        }
    }
}
