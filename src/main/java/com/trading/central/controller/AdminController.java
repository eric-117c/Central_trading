package com.trading.central.controller;

import com.trading.central.engine.MatchingEngine;
import com.trading.central.engine.MatchingEngine.AuctionResult;
import com.trading.central.engine.PriceLimiter;
import com.trading.central.service.StockService;
import com.trading.central.service.TradeService;
import com.trading.central.util.Constants.TradeStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/central-trading/admin")
public class AdminController {

    private final MatchingEngine matchingEngine;
    private final PriceLimiter priceLimiter;
    private final StockService stockService;
    private final TradeService tradeService;

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public AdminController(MatchingEngine matchingEngine, PriceLimiter priceLimiter,
                           StockService stockService, TradeService tradeService) {
        this.matchingEngine = matchingEngine;
        this.priceLimiter = priceLimiter;
        this.stockService = stockService;
        this.tradeService = tradeService;
    }

    @PostMapping("/stocks/{stockCode}/price-limit")
    public ResponseEntity<Map<String, Object>> setPriceLimit(@PathVariable String stockCode, @RequestBody Map<String, Object> body) {
        String stockType = body.containsKey("stockType") ? body.get("stockType").toString() : null;
        Object limitRateObj = body.get("limitRate");

        if (stockType == null || limitRateObj == null) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "需要 stockType (NORMAL/ST) 和 limitRate");
            return ResponseEntity.badRequest().body(res);
        }

        BigDecimal limitRate = new BigDecimal(limitRateObj.toString());
        priceLimiter.updateLimitRate(stockType, limitRate);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", stockType + " 类型涨跌停幅度已设置为 " + limitRate.multiply(new BigDecimal("100")).setScale(1, java.math.RoundingMode.HALF_UP) + "%（次日生效）");
        return ResponseEntity.ok(res);
    }

    @PostMapping("/stocks/{stockCode}/suspend")
    public ResponseEntity<Map<String, Object>> suspendStock(@PathVariable String stockCode) {
        stockService.setTradeStatus(stockCode, TradeStatus.SUSPENDED.name());
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", stockCode + " 交易已暂停");
        return ResponseEntity.ok(res);
    }

    @PostMapping("/stocks/{stockCode}/resume")
    public ResponseEntity<Map<String, Object>> resumeStock(@PathVariable String stockCode) {
        stockService.setTradeStatus(stockCode, TradeStatus.TRADING.name());
        stockService.queryAndSendQuote(stockCode);
        
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", stockCode + " 交易已重启");
        return ResponseEntity.ok(res);
    }

    @GetMapping("/stocks/{stockCode}/orders")
    public ResponseEntity<Map<String, Object>> getOrders(@PathVariable String stockCode) {
        Map<String, Object> snapshot = matchingEngine.getBookSnapshot(stockCode);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", snapshot);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/kafka/status")
    public ResponseEntity<Map<String, Object>> getKafkaStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", kafkaEnabled);
        data.put("status", kafkaEnabled ? "CONNECTED" : "DISABLED");
        
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", data);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/price-limits/refresh")
    public ResponseEntity<Map<String, Object>> refreshPriceLimits() {
        priceLimiter.refreshAllLimits();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "涨跌停缓存已刷新");
        return ResponseEntity.ok(res);
    }

    // ================================================================
    // 集合竞价管理
    // ================================================================

    @PostMapping("/call-auction/enter")
    public ResponseEntity<Map<String, Object>> enterCallAuction() {
        matchingEngine.enterCallAuction();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "已进入集合竞价阶段");
        res.put("data", Map.of("phase", matchingEngine.getCurrentPhase().name()));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/call-auction/trigger")
    public ResponseEntity<Map<String, Object>> triggerCallAuction(
            @RequestBody(required = false) Map<String, Object> body) {

        String targetStock = body != null ? (String) body.get("stockCode") : null;

        if (targetStock != null && !targetStock.isBlank()) {
            // 对指定股票执行集合竞价
            return runAuctionForStock(targetStock);
        }

        // 对所有有集合竞价订单的股票执行
        Map<String, Object> summary = new HashMap<>();
        summary.put("phase", matchingEngine.getCurrentPhase().name());
        int successCount = 0;
        int totalTrades = 0;

        for (String stockCode : matchingEngine.getCallAuctionStockCodes()) {
            ResponseEntity<Map<String, Object>> result = runAuctionForStock(stockCode);
            if (Boolean.TRUE.equals(result.getBody().get("success"))) {
                successCount++;
                Object data = result.getBody().get("data");
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> d = (Map<String, Object>) data;
                    totalTrades += ((Number) d.getOrDefault("totalTradeQty", 0)).intValue();
                }
            }
        }

        matchingEngine.enterContinuousAuction();

        summary.put("success", true);
        summary.put("stocksProcessed", successCount);
        summary.put("totalTradeQty", totalTrades);
        summary.put("message", String.format("集合竞价完成：%d 只股票，总成交量 %d", successCount, totalTrades));
        return ResponseEntity.ok(summary);
    }

    private ResponseEntity<Map<String, Object>> runAuctionForStock(String stockCode) {
        try {
            PriceLimiter.Limits limits = priceLimiter.getPriceLimits(stockCode);
            if (limits == null) {
                Map<String, Object> res = new HashMap<>();
                res.put("success", false);
                res.put("message", "股票 " + stockCode + " 不存在");
                return ResponseEntity.badRequest().body(res);
            }

            AuctionResult result = matchingEngine.runCallAuction(
                    stockCode, limits.getPreviousClose(), tradeService::executeTrade);

            Map<String, Object> data = new HashMap<>();
            if (result != null) {
                data.put("auctionPrice", result.auctionPrice);
                data.put("tradeCount", result.trades.size());
                data.put("totalTradeQty", result.totalTradeQty);
                data.put("remainingCount", result.remainingCount);
            } else {
                data.put("message", "该股票无集合竞价订单");
            }

            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("data", data);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            log.error("[AdminController] 集合竞价执行失败: {}", stockCode, e);
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "集合竞价执行失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(res);
        }
    }

    @GetMapping("/call-auction/status")
    public ResponseEntity<Map<String, Object>> getCallAuctionStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", matchingEngine.getCurrentPhase().name());
        data.put("stocksWithOrders", matchingEngine.getCallAuctionStockCodes());
        data.put("stockCount", matchingEngine.getCallAuctionStockCodes().size());

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", data);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/call-auction/orders/{stockCode}")
    public ResponseEntity<Map<String, Object>> getCallAuctionOrders(@PathVariable String stockCode) {
        Map<String, Object> snapshot = matchingEngine.getCallAuctionSnapshot(stockCode);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("data", snapshot);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/stocks")
    public ResponseEntity<Map<String, Object>> addStock(@RequestBody Map<String, Object> body) {
        String stockCode = body.containsKey("stockCode") ? body.get("stockCode").toString().trim() : null;
        String stockName = body.containsKey("stockName") ? body.get("stockName").toString().trim() : null;
        String stockType = body.containsKey("stockType") ? body.get("stockType").toString().trim() : "NORMAL";
        Object previousCloseObj = body.get("previousClose");
        String notice = body.containsKey("notice") ? body.get("notice").toString().trim() : "";

        if (stockCode == null || stockCode.isEmpty() || stockName == null || stockName.isEmpty() || previousCloseObj == null) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "stockCode、stockName、previousClose 为必填项");
            return ResponseEntity.badRequest().body(res);
        }

        if (!stockCode.matches("^\\d{6}$")) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "股票代码必须为6位数字");
            return ResponseEntity.badRequest().body(res);
        }

        if (!"NORMAL".equals(stockType) && !"ST".equals(stockType)) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "stockType 必须为 NORMAL 或 ST");
            return ResponseEntity.badRequest().body(res);
        }

        BigDecimal previousClose;
        try {
            previousClose = new BigDecimal(previousCloseObj.toString());
            if (previousClose.compareTo(BigDecimal.ZERO) <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "previousClose 必须为大于0的数值");
            return ResponseEntity.badRequest().body(res);
        }

        if (stockService.stockExists(stockCode)) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("message", "股票 " + stockCode + " 已存在");
            return ResponseEntity.status(409).body(res);
        }

        stockService.addStock(stockCode, stockName, stockType, previousClose, notice);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "股票入库成功: " + stockCode + " " + stockName);
        return ResponseEntity.ok(res);
    }
}
