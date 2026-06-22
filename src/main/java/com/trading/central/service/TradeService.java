package com.trading.central.service;

import com.trading.central.dashboard.TradingEventBroadcaster;
import com.trading.central.model.OrderEntry;
import com.trading.central.model.TradeReportMsg;
import com.trading.central.model.StockQuoteMsg;
import com.trading.central.kafka.KafkaProducerService;
import com.trading.central.util.Constants.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class TradeService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountService accountService;
    private final StockService stockService;
    private final KafkaProducerService kafkaProducerService;
    private final TradingEventBroadcaster broadcaster;

    private int tradeSeq;

    public TradeService(JdbcTemplate jdbcTemplate, AccountService accountService, StockService stockService, KafkaProducerService kafkaProducerService, TradingEventBroadcaster broadcaster) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountService = accountService;
        this.stockService = stockService;
        this.kafkaProducerService = kafkaProducerService;
        this.broadcaster = broadcaster;

        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "T" + dateStr;
        try {
            Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(CAST(SUBSTRING(trade_no, 10) AS UNSIGNED)) FROM trade_record WHERE trade_no LIKE ?",
                Integer.class, prefix + "%");
            this.tradeSeq = maxSeq != null ? maxSeq : 0;
        } catch (Exception e) {
            this.tradeSeq = 0;
        }
        log.info("[TradeService] 初始化 tradeSeq={}", this.tradeSeq);
    }

    private synchronized String generateTradeNo() {
        tradeSeq++;
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("T%s%04d", dateStr, tradeSeq);
    }

    public void executeTrade(OrderEntry buyOrder, OrderEntry sellOrder, BigDecimal tradePrice, int tradeQty) {
        String tradeNo = generateTradeNo();
        String tradeTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        BigDecimal tradeAmount = tradePrice.multiply(new BigDecimal(tradeQty)).setScale(2, RoundingMode.HALF_UP);

        // 1. 写入成交记录
        jdbcTemplate.update(
            "INSERT INTO trade_record (trade_no, buyer_order_id, seller_order_id, stock_code, trade_price, trade_quantity, trade_amount, trade_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            tradeNo, buyOrder.getOrderId(), sellOrder.getOrderId(), buyOrder.getStockCode(), tradePrice, tradeQty, tradeAmount, tradeTime
        );

        // 2. 更新最新成交价和价格历史
        stockService.updateLatestPrice(buyOrder.getStockCode(), tradePrice);
        stockService.recordPriceHistory(buyOrder.getStockCode(), tradePrice, tradeTime);

        // 3. 更新双方委托在数据库中的状态
        updateOrderInDb(buyOrder);
        updateOrderInDb(sellOrder);

        // 4. 调用账户系统：买方扣划冻结资金 + 增加持仓
        try {
            accountService.settleBuyFunds(buyOrder.getAccountId(), tradeAmount);
            accountService.settleBuyerHolding(buyOrder.getAccountId(), buyOrder.getStockCode(), tradeQty);
        } catch (Exception err) {
            log.error("[TradeService] 买方账户更新失败: {}", buyOrder.getOrderId(), err);
        }

        // 5. 调用账户系统：卖方扣减冻结持仓 + 回款
        try {
            accountService.settleSellerHolding(sellOrder.getAccountId(), sellOrder.getStockCode(), tradeQty);
            accountService.settleSellFunds(sellOrder.getAccountId(), tradeAmount);
        } catch (Exception err) {
            log.error("[TradeService] 卖方账户更新失败: {}", sellOrder.getOrderId(), err);
        }

        // 6. 发送成交反馈
        TradeReportMsg tradeMsg = new TradeReportMsg();
        tradeMsg.setTradeNo(tradeNo);
        tradeMsg.setBuyerOrderId(buyOrder.getOrderId());
        tradeMsg.setSellerOrderId(sellOrder.getOrderId());
        tradeMsg.setStockCode(buyOrder.getStockCode());
        tradeMsg.setTradePrice(tradePrice);
        tradeMsg.setTradeQuantity(tradeQty);
        tradeMsg.setTradeTime(tradeTime);
        tradeMsg.setBuyerName(accountService.getAccountName(buyOrder.getAccountId()));
        tradeMsg.setSellerName(accountService.getAccountName(sellOrder.getAccountId()));
        kafkaProducerService.sendTradeReport(tradeMsg);

        // 7. 发送订单状态更新
        kafkaProducerService.sendOrderReport(buyOrder.getOrderId(), buyOrder.getStatus(), 
            OrderStatus.TRADED.name().equals(buyOrder.getStatus()) ? "全部成交" : "部分成交 " + buyOrder.getFilledQuantity() + "/" + buyOrder.getQuantity());

        kafkaProducerService.sendOrderReport(sellOrder.getOrderId(), sellOrder.getStatus(), 
            OrderStatus.TRADED.name().equals(sellOrder.getStatus()) ? "全部成交" : "部分成交 " + sellOrder.getFilledQuantity() + "/" + sellOrder.getQuantity());

        // 8. 推送最新行情
        pushLatestQuote(buyOrder.getStockCode(), tradePrice);

        log.info("[TradeService] 成交完成: {} {} {}x{} 买方={} 卖方={}", tradeNo, buyOrder.getStockCode(), tradePrice, tradeQty, buyOrder.getOrderId(), sellOrder.getOrderId());
        broadcaster.match(buyOrder.getStockCode(),
                tradePrice.toPlainString(), String.valueOf(tradeQty),
                buyOrder.getAccountId(), sellOrder.getAccountId(),
                "成交完成 " + tradeNo);
    }

    private void updateOrderInDb(OrderEntry order) {
        jdbcTemplate.update(
            "UPDATE order_book SET filled_quantity = ?, remaining_quantity = ?, status = ?, update_time = NOW() WHERE order_id = ?",
            order.getFilledQuantity(), order.getRemainingQuantity(), order.getStatus(), order.getOrderId()
        );
    }

    private void pushLatestQuote(String stockCode, BigDecimal latestPrice) {
        try {
            StockQuoteMsg quote = stockService.buildQuote(stockCode);
            if (quote != null) {
                quote.setLatestPrice(latestPrice);
                kafkaProducerService.sendStockQuote(quote);
            }
        } catch (Exception err) {
            log.error("[TradeService] 推送行情失败: {}", stockCode, err);
        }
    }

    public synchronized void resetTradeSeq() {
        this.tradeSeq = 0;
    }
}
