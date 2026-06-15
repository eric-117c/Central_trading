package com.trading.central.kafka;

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

    public KafkaConsumerService(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(topics = Topics.ORDER_COMMAND, autoStartup = "${app.kafka.enabled:false}")
    public void onOrderCommand(OrderCommandMsg msg) {
        try {
            orderService.receiveOrder(msg);
        } catch (Exception e) {
            log.error("[Kafka] 处理委托消息异常: {}", msg, e);
        }
    }

    @KafkaListener(topics = Topics.CANCEL_COMMAND, autoStartup = "${app.kafka.enabled:false}")
    public void onCancelCommand(CancelCommandMsg msg) {
        try {
            orderService.cancelOrder(msg);
        } catch (Exception e) {
            log.error("[Kafka] 处理撤单消息异常: {}", msg, e);
        }
    }

    @KafkaListener(topics = Topics.STOCK_QUERY, autoStartup = "${app.kafka.enabled:false}")
    public void onStockQuery(StockQueryMsg msg) {
        try {
            orderService.handleStockQuery(msg);
        } catch (Exception e) {
            log.error("[Kafka] 处理行情查询消息异常: {}", msg, e);
        }
    }
}
