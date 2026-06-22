package com.trading.central.service;

import com.trading.central.engine.MatchingEngine;
import com.trading.central.kafka.KafkaProducerService;
import com.trading.central.model.StockInfo;
import com.trading.central.model.StockQuoteMsg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StockService {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingEngine matchingEngine;
    private final KafkaProducerService kafkaProducerService;

    public StockService(JdbcTemplate jdbcTemplate, MatchingEngine matchingEngine, KafkaProducerService kafkaProducerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.matchingEngine = matchingEngine;
        this.kafkaProducerService = kafkaProducerService;
    }

    public StockInfo getStockInfo(String stockCode) {
        String sql = "SELECT stock_code, stock_name, stock_type, previous_close, latest_price, open_price, trade_status, notice FROM stock_info WHERE stock_code = ?";
        List<StockInfo> list = jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(StockInfo.class), stockCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<StockInfo> searchStocks(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return jdbcTemplate.query("SELECT stock_code, stock_name, stock_type, previous_close, latest_price, open_price, trade_status, notice FROM stock_info ORDER BY stock_code LIMIT 50", new BeanPropertyRowMapper<>(StockInfo.class));
        }
        String sql = "SELECT stock_code, stock_name, stock_type, previous_close, latest_price, open_price, trade_status, notice FROM stock_info WHERE stock_code = ? OR stock_name LIKE ? LIMIT 50";
        return jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(StockInfo.class), keyword, "%" + keyword + "%");
    }

    public Map<String, BigDecimal> getDayHighLow(String stockCode) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String sql = "SELECT MAX(trade_price) AS highestPrice, MIN(trade_price) AS lowestPrice FROM trade_price_history WHERE stock_code = ? AND DATE(trade_time) = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, stockCode, today);
        if (!rows.isEmpty() && rows.get(0).get("highestPrice") != null) {
            return Map.of(
                "highestPrice", new BigDecimal(rows.get(0).get("highestPrice").toString()),
                "lowestPrice", new BigDecimal(rows.get(0).get("lowestPrice").toString())
            );
        }
        return null;
    }

    public StockQuoteMsg queryAndSendQuote(String stockCode) {
        StockQuoteMsg quote = buildQuote(stockCode);
        if (quote == null) {
            log.warn("[StockService] 股票 {} 不存在", stockCode);
            return null;
        }
        kafkaProducerService.sendStockQuote(quote);
        return quote;
    }

    public StockQuoteMsg buildQuote(String stockCode) {
        StockInfo info = getStockInfo(stockCode);
        if (info == null) return null;

        Map<String, BigDecimal> dayHL = getDayHighLow(stockCode);
        Map<String, BigDecimal> topPrices = matchingEngine.getTopPrices(stockCode);

        StockQuoteMsg quote = new StockQuoteMsg();
        quote.setStockCode(info.getStockCode());
        quote.setStockName(info.getStockName());
        quote.setLatestPrice(info.getLatestPrice());
        quote.setPreviousClose(info.getPreviousClose());

        quote.setHighestPrice(dayHL != null ? dayHL.get("highestPrice") : info.getLatestPrice());
        quote.setLowestPrice(dayHL != null ? dayHL.get("lowestPrice") : info.getLatestPrice());

        quote.setBidPrice(topPrices.get("bidPrice") != null ? topPrices.get("bidPrice") : info.getLatestPrice());
        quote.setAskPrice(topPrices.get("askPrice") != null ? topPrices.get("askPrice") : info.getLatestPrice());

        quote.setTradeStatus("TRADING".equals(info.getTradeStatus()) ? "可交易" : "停牌");
        quote.setNotice(info.getNotice() != null ? info.getNotice() : "");
        quote.setQuoteTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        return quote;
    }

    public void updateLatestPrice(String stockCode, BigDecimal price) {
        jdbcTemplate.update("UPDATE stock_info SET latest_price = ?, update_time = NOW() WHERE stock_code = ?", price, stockCode);
    }

    public void recordPriceHistory(String stockCode, BigDecimal price, String tradeTime) {
        jdbcTemplate.update("INSERT INTO trade_price_history (stock_code, trade_price, trade_time) VALUES (?, ?, ?)", stockCode, price, tradeTime);
    }

    public void setTradeStatus(String stockCode, String status) {
        jdbcTemplate.update("UPDATE stock_info SET trade_status = ?, update_time = NOW() WHERE stock_code = ?", status, stockCode);
        log.info("[StockService] {} 交易状态更新为 {}", stockCode, status);
    }

    public boolean stockExists(String stockCode) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_info WHERE stock_code = ?", Integer.class, stockCode);
        return count != null && count > 0;
    }

    public void addStock(String stockCode, String stockName, String stockType, BigDecimal previousClose, String notice) {
        jdbcTemplate.update(
            "INSERT INTO stock_info (stock_code, stock_name, stock_type, previous_close, latest_price, open_price, trade_status, notice) VALUES (?, ?, ?, ?, ?, ?, 'TRADING', ?)",
            stockCode, stockName, stockType, previousClose, previousClose, previousClose, notice
        );
        log.info("[StockService] 股票入库成功: {} {}", stockCode, stockName);
    }
}
