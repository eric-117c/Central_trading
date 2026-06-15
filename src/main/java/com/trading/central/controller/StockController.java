package com.trading.central.controller;

import com.trading.central.model.StockInfo;
import com.trading.central.model.StockQuoteMsg;
import com.trading.central.service.StockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/central-trading/stocks")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping
    public ResponseEntity<List<StockQuoteMsg>> getStocks(@RequestParam(required = false) String keyword) {
        List<StockInfo> stocks = stockService.searchStocks(keyword);
        List<StockQuoteMsg> quotes = new ArrayList<>();

        for (StockInfo stock : stocks) {
            StockQuoteMsg quote = stockService.buildQuote(stock.getStockCode());
            if (quote != null) {
                quotes.add(quote);
            }
        }
        return ResponseEntity.ok(quotes);
    }

    @GetMapping("/{stockCode}")
    public ResponseEntity<Map<String, Object>> getStockDetail(@PathVariable String stockCode) {
        StockQuoteMsg quote = stockService.buildQuote(stockCode);
        if (quote == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "股票不存在");
            return ResponseEntity.status(404).body(error);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", quote);
        return ResponseEntity.ok(response);
    }
}
