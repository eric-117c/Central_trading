package com.trading.central.controller;

import com.trading.central.engine.MatchingEngine;
import com.trading.central.model.OrderEntry;
import com.trading.central.model.StockInfo;
import com.trading.central.service.AccountService;
import com.trading.central.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/central-trading/market")
public class MarketController {

    private final MatchingEngine matchingEngine;
    private final JdbcTemplate jdbcTemplate;
    private final StockService stockService;
    private final AccountService accountService;

    public MarketController(MatchingEngine matchingEngine, JdbcTemplate jdbcTemplate,
                            StockService stockService, AccountService accountService) {
        this.matchingEngine = matchingEngine;
        this.jdbcTemplate = jdbcTemplate;
        this.stockService = stockService;
        this.accountService = accountService;
    }

    @GetMapping("/snapshot/{stockCode}")
    public Map<String, Object> getSnapshot(@PathVariable String stockCode) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stockCode", stockCode);
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        StockInfo info = stockService.getStockInfo(stockCode);
        response.put("stockName", info != null ? info.getStockName() : "");

        BigDecimal bidPrice = null;
        Integer bidVolume = null;
        BigDecimal askPrice = null;
        Integer askVolume = null;

        try {
            Map<String, Object> bookSnapshot = matchingEngine.getBookSnapshot(stockCode);
            if (bookSnapshot != null) {
                @SuppressWarnings("unchecked")
                List<OrderEntry> buyOrders = (List<OrderEntry>) bookSnapshot.get("buyOrders");
                @SuppressWarnings("unchecked")
                List<OrderEntry> sellOrders = (List<OrderEntry>) bookSnapshot.get("sellOrders");

                if (buyOrders != null && !buyOrders.isEmpty()) {
                    OrderEntry bestBid = buyOrders.get(0);
                    bidPrice = bestBid.getPrice();
                    bidVolume = bestBid.getRemainingQuantity();
                }
                if (sellOrders != null && !sellOrders.isEmpty()) {
                    OrderEntry bestAsk = sellOrders.get(0);
                    askPrice = bestAsk.getPrice();
                    askVolume = bestAsk.getRemainingQuantity();
                }
            }
        } catch (Exception e) {
            log.warn("[MarketController] getBookSnapshot failed: {}", stockCode, e);
        }

        response.put("bidPrice", bidPrice);
        response.put("bidVolume", bidVolume);
        response.put("askPrice", askPrice);
        response.put("askVolume", askVolume);

        List<Map<String, Object>> recentTrades = new ArrayList<>();
        try {
            String sql = "SELECT trade_no, buyer_order_id, seller_order_id, stock_code, trade_price, trade_quantity, trade_time " +
                         "FROM trade_record WHERE stock_code = ? ORDER BY trade_time DESC LIMIT 10";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, stockCode);

            for (Map<String, Object> row : rows) {
                Map<String, Object> trade = new LinkedHashMap<>();
                trade.put("tradeNo", row.get("trade_no"));
                String buyerId = row.get("buyer_order_id").toString();
                String sellerId = row.get("seller_order_id").toString();
                trade.put("buyerName", accountService.getAccountName(buyerId));
                trade.put("sellerName", accountService.getAccountName(sellerId));
                trade.put("stockCode", row.get("stock_code"));
                trade.put("dealPrice", new BigDecimal(row.get("trade_price").toString()));
                trade.put("dealQuantity", Integer.parseInt(row.get("trade_quantity").toString()));
                trade.put("tradeTime", row.get("trade_time"));
                recentTrades.add(trade);
            }
        } catch (Exception e) {
            log.warn("[MarketController] query trade_record failed: {}", stockCode, e);
        }

        response.put("recentTrades", recentTrades);

        return response;
    }
}
