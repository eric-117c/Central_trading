package com.trading.central.controller;

import com.trading.central.engine.MatchingEngine;
import com.trading.central.engine.PriceLimiter;
import com.trading.central.service.StockService;
import com.trading.central.util.Constants.TradeStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/central-trading/admin")
public class AdminController {

    private final MatchingEngine matchingEngine;
    private final PriceLimiter priceLimiter;
    private final StockService stockService;

    @Value("${app.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public AdminController(MatchingEngine matchingEngine, PriceLimiter priceLimiter, StockService stockService) {
        this.matchingEngine = matchingEngine;
        this.priceLimiter = priceLimiter;
        this.stockService = stockService;
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
        res.put("message", stockType + " 类型涨跌停幅度已设置为 " + limitRate.multiply(new BigDecimal("100")).setScale(1, BigDecimal.ROUND_HALF_UP) + "%（次日生效）");
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
}
