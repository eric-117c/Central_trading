package com.trading.central.service;

import com.trading.central.dashboard.TradingEventBroadcaster;
import com.trading.central.engine.MatchingEngine;
import com.trading.central.engine.PriceLimiter;
import com.trading.central.kafka.KafkaProducerService;
import com.trading.central.model.OrderCommandMsg;
import com.trading.central.model.CancelCommandMsg;
import com.trading.central.model.StockQueryMsg;
import com.trading.central.model.OrderEntry;
import com.trading.central.model.StockInfo;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;
import com.trading.central.util.Constants.TradeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingEngine matchingEngine;
    private final PriceLimiter priceLimiter;
    private final TradeService tradeService;
    private final StockService stockService;
    private final AccountService accountService;
    private final KafkaProducerService kafkaProducerService;
    private final TradingEventBroadcaster broadcaster;

    public OrderService(JdbcTemplate jdbcTemplate, MatchingEngine matchingEngine, PriceLimiter priceLimiter, TradeService tradeService, StockService stockService, AccountService accountService, KafkaProducerService kafkaProducerService, TradingEventBroadcaster broadcaster) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchingEngine = matchingEngine;
        this.priceLimiter = priceLimiter;
        this.tradeService = tradeService;
        this.stockService = stockService;
        this.accountService = accountService;
        this.kafkaProducerService = kafkaProducerService;
        this.broadcaster = broadcaster;
    }

    public void receiveOrder(OrderCommandMsg msg) {
        if (msg.getAccountId() == null || msg.getOrderId() == null || msg.getStockCode() == null || msg.getSide() == null || msg.getPrice() == null || msg.getQuantity() == null) {
            log.warn("[OrderService] 委托参数不完整: {}", msg.getOrderId());
            kafkaProducerService.sendOrderReport(msg.getOrderId() != null ? msg.getOrderId() : "UNKNOWN", OrderStatus.REJECTED.name(), "委托参数不完整");
            return;
        }

        if (!Side.BUY.name().equals(msg.getSide()) && !Side.SELL.name().equals(msg.getSide())) {
            kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "无效的买卖方向: " + msg.getSide());
            return;
        }

        StockInfo stockInfo = stockService.getStockInfo(msg.getStockCode());
        if (stockInfo == null) {
            kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "股票 " + msg.getStockCode() + " 不存在");
            return;
        }

        if (TradeStatus.SUSPENDED.name().equals(stockInfo.getTradeStatus())) {
            kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "股票 " + msg.getStockCode() + " 已停牌，暂停交易");
            return;
        }

        PriceLimiter.ValidationResult priceCheck = priceLimiter.validateOrderPrice(msg.getStockCode(), msg.getPrice());
        if (!priceCheck.valid) {
            kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), priceCheck.reason);
            return;
        }

        try {
            if (Side.BUY.name().equals(msg.getSide())) {
                BigDecimal freezeAmount = msg.getPrice().multiply(new BigDecimal(msg.getQuantity()));
                accountService.freezeFunds(msg.getAccountId(), freezeAmount);
            } else {
                accountService.freezeHolding(msg.getAccountId(), msg.getStockCode(), msg.getQuantity());
            }
        } catch (Exception err) {
            kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "冻结失败: " + err.getMessage());
            return;
        }

        LocalDateTime entryTime = msg.getTimestamp() != null ? LocalDateTime.parse(msg.getTimestamp(), DateTimeFormatter.ISO_DATE_TIME) : LocalDateTime.now();
        LocalDate tradeDate = LocalDate.now();

        try {
            jdbcTemplate.update(
                "INSERT INTO order_book (order_id, account_id, stock_code, side, price, quantity, filled_quantity, remaining_quantity, status, entry_time, update_time, trade_date) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)",
                msg.getOrderId(), msg.getAccountId(), msg.getStockCode(), msg.getSide(), msg.getPrice(), msg.getQuantity(), msg.getQuantity(), OrderStatus.ACCEPTED.name(), entryTime, entryTime, tradeDate
            );
        } catch (Exception err) {
            if (err.getMessage() != null && err.getMessage().contains("Duplicate entry")) {
                kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "重复的委托编号");
            } else {
                kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "系统错误: " + err.getMessage());
            }
            try {
                if (Side.BUY.name().equals(msg.getSide())) {
                    accountService.releaseFunds(msg.getAccountId(), msg.getPrice().multiply(new BigDecimal(msg.getQuantity())));
                } else {
                    accountService.releaseHolding(msg.getAccountId(), msg.getStockCode(), msg.getQuantity());
                }
            } catch (Exception rollbackErr) {
                log.error("[OrderService] 冻结回滚失败: {}", msg.getOrderId(), rollbackErr);
            }
            return;
        }

        kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.ACCEPTED.name(), "委托已受理");

        OrderEntry orderEntry = new OrderEntry();
        orderEntry.setOrderId(msg.getOrderId());
        orderEntry.setAccountId(msg.getAccountId());
        orderEntry.setStockCode(msg.getStockCode());
        orderEntry.setSide(msg.getSide());
        orderEntry.setPrice(msg.getPrice());
        orderEntry.setQuantity(msg.getQuantity());
        orderEntry.setFilledQuantity(0);
        orderEntry.setRemainingQuantity(msg.getQuantity());
        orderEntry.setStatus(OrderStatus.ACCEPTED.name());
        orderEntry.setEntryTime(entryTime);

        broadcaster.order(msg.getStockCode(), msg.getOrderId(), msg.getAccountId(),
                msg.getSide(), msg.getPrice().toPlainString(), String.valueOf(msg.getQuantity()),
                "委托已受理");

        try {
            if (matchingEngine.getCurrentPhase() == MatchingEngine.AuctionPhase.CALL_AUCTION) {
                matchingEngine.addToCallAuction(orderEntry);
                log.info("[OrderService]  已加入集合竞价池（{}）", msg.getOrderId(), msg.getStockCode());
            } else {
                MatchingEngine.MatchResult result = matchingEngine.matchOrder(orderEntry, tradeService::executeTrade);
                if (result.trades.isEmpty()) {
                    log.info("[OrderService] {} 无匹配对手方，已挂单等待", msg.getOrderId());
                }
            }
        } catch (Exception e) {
            log.error("[OrderService] 撮合异常", e);
        }
    }

    public void cancelOrder(CancelCommandMsg msg) {
        if (msg.getOrderId() == null) {
            log.warn("[OrderService] 撤单缺少 orderId");
            return;
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT order_id, account_id, stock_code, side, price, quantity, filled_quantity, remaining_quantity, status FROM order_book WHERE order_id = ?",
            msg.getOrderId()
        );

        if (rows.isEmpty()) {
            kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "委托不存在");
            return;
        }

        Map<String, Object> order = rows.get(0);
        String status = order.get("status").toString();

        if (OrderStatus.TRADED.name().equals(status) || OrderStatus.CANCELED.name().equals(status) || OrderStatus.EXPIRED.name().equals(status)) {
            kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.REJECTED.name(), "委托状态为 " + status + "，无法撤单");
            return;
        }

        matchingEngine.cancelOrderInBook(msg.getOrderId(), order.get("stock_code").toString());

        jdbcTemplate.update("UPDATE order_book SET status = ?, update_time = NOW() WHERE order_id = ?", OrderStatus.CANCELED.name(), msg.getOrderId());

        int remainQty = Integer.parseInt(order.get("remaining_quantity").toString());
        BigDecimal price = new BigDecimal(order.get("price").toString());

        try {
            if (Side.BUY.name().equals(order.get("side").toString())) {
                accountService.releaseFunds(order.get("account_id").toString(), price.multiply(new BigDecimal(remainQty)));
            } else {
                accountService.releaseHolding(order.get("account_id").toString(), order.get("stock_code").toString(), remainQty);
            }
        } catch (Exception err) {
            log.error("[OrderService] 撤单释放资源失败: {}", msg.getOrderId(), err);
        }

        kafkaProducerService.sendOrderReport(msg.getOrderId(), OrderStatus.CANCELED.name(), "用户撤单成功");
        log.info("[OrderService] 撤单成功: {}", msg.getOrderId());
        broadcaster.cancel(order.get("stock_code").toString(), msg.getOrderId(), msg.getAccountId(), "用户撤单成功");
    }

    public void handleStockQuery(StockQueryMsg msg) {
        if (msg.getStockCode() == null) {
            log.warn("[OrderService] 行情查询缺少 stockCode");
            return;
        }
        stockService.queryAndSendQuote(msg.getStockCode());
        log.debug("[OrderService] 行情查询已处理: {}", msg.getStockCode());
    }

    public Map<String, Object> buildOrderFeedback(String orderId) {
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
            "SELECT order_id, account_id, stock_code, side, price, quantity, filled_quantity, remaining_quantity, status, reject_reason, entry_time, update_time " +
            "FROM order_book WHERE order_id = ?", orderId
        );

        if (orders.isEmpty()) {
            return null;
        }

        Map<String, Object> order = orders.get(0);
        List<Map<String, Object>> trades = jdbcTemplate.queryForList(
            "SELECT trade_no, trade_price, trade_quantity, trade_amount, trade_time " +
            "FROM trade_record WHERE buyer_order_id = ? OR seller_order_id = ? ORDER BY trade_time",
            orderId, orderId
        );

        BigDecimal weightedAmount = BigDecimal.ZERO;
        int tradedQuantity = 0;
        for (Map<String, Object> trade : trades) {
            BigDecimal price = new BigDecimal(trade.get("trade_price").toString());
            int quantity = Integer.parseInt(trade.get("trade_quantity").toString());
            weightedAmount = weightedAmount.add(price.multiply(new BigDecimal(quantity)));
            tradedQuantity += quantity;
        }

        BigDecimal averageTradePrice = null;
        if (tradedQuantity > 0) {
            averageTradePrice = weightedAmount.divide(new BigDecimal(tradedQuantity), 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> feedback = new HashMap<>();
        feedback.put("orderId", order.get("order_id"));
        feedback.put("accountId", order.get("account_id"));
        feedback.put("stockCode", order.get("stock_code"));
        feedback.put("side", order.get("side"));
        feedback.put("orderPrice", new BigDecimal(order.get("price").toString()));
        feedback.put("orderQuantity", Integer.parseInt(order.get("quantity").toString()));
        feedback.put("filledQuantity", Integer.parseInt(order.get("filled_quantity").toString()));
        feedback.put("remainingQuantity", Integer.parseInt(order.get("remaining_quantity").toString()));
        feedback.put("status", order.get("status"));
        feedback.put("reason", order.get("reject_reason") != null ? order.get("reject_reason") : "");
        feedback.put("averageTradePrice", averageTradePrice);
        feedback.put("lastTradeTime", trades.isEmpty() ? null : trades.get(trades.size() - 1).get("trade_time"));
        feedback.put("entryTime", order.get("entry_time"));
        feedback.put("updateTime", order.get("update_time"));
        feedback.put("trades", trades.stream().map(trade -> {
            Map<String, Object> item = new HashMap<>();
            item.put("tradeNo", trade.get("trade_no"));
            item.put("tradePrice", new BigDecimal(trade.get("trade_price").toString()));
            item.put("tradeQuantity", Integer.parseInt(trade.get("trade_quantity").toString()));
            item.put("tradeAmount", new BigDecimal(trade.get("trade_amount").toString()));
            item.put("tradeTime", trade.get("trade_time"));
            return item;
        }).collect(Collectors.toList()));

        return feedback;
    }

    public Map<String, Object> pushOrderFeedback(String orderId) {
        Map<String, Object> feedback = buildOrderFeedback(orderId);
        if (feedback == null) {
            kafkaProducerService.sendOrderReport(orderId, OrderStatus.REJECTED.name(), "委托不存在");
            return null;
        }

        String status = feedback.get("status").toString();
        String reason = buildFeedbackReason(feedback);
        kafkaProducerService.sendOrderReport(orderId, status, reason);
        return feedback;
    }

    private String buildFeedbackReason(Map<String, Object> feedback) {
        String status = feedback.get("status").toString();
        int filledQuantity = Integer.parseInt(feedback.get("filledQuantity").toString());
        int orderQuantity = Integer.parseInt(feedback.get("orderQuantity").toString());
        Object averageTradePrice = feedback.get("averageTradePrice");

        if (OrderStatus.TRADED.name().equals(status)) {
            return "全部成交 " + filledQuantity + "/" + orderQuantity + (averageTradePrice != null ? "，均价 " + averageTradePrice : "");
        }
        if (OrderStatus.PART_TRADED.name().equals(status)) {
            return "部分成交 " + filledQuantity + "/" + orderQuantity + (averageTradePrice != null ? "，均价 " + averageTradePrice : "");
        }
        if (OrderStatus.EXPIRED.name().equals(status)) {
            return "当日委托已过期";
        }
        if (OrderStatus.CANCELED.name().equals(status)) {
            return "用户撤单成功";
        }
        Object reason = feedback.get("reason");
        return reason != null && !reason.toString().isBlank() ? reason.toString() : "委托状态: " + status;
    }
}
