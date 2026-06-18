package com.trading.central.engine;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PriceLimiter {

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Limits> limitsCache = new ConcurrentHashMap<>();

    public PriceLimiter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Data
    public static class Limits {
        private BigDecimal previousClose;
        private BigDecimal limitRate;
        private BigDecimal upperLimit;
        private BigDecimal lowerLimit;
    }

    public static class ValidationResult {
        public boolean valid;
        public String reason;
        public BigDecimal upperLimit;
        public BigDecimal lowerLimit;
        
        public ValidationResult(boolean valid, String reason, BigDecimal upperLimit, BigDecimal lowerLimit) {
            this.valid = valid;
            this.reason = reason;
            this.upperLimit = upperLimit;
            this.lowerLimit = lowerLimit;
        }
    }

    public Limits loadPriceLimits(String stockCode) {
        String sql = "SELECT s.stock_code, s.stock_type, s.previous_close, " +
                     "COALESCE(p.limit_rate, ?) AS limit_rate " +
                     "FROM stock_info s " +
                     "LEFT JOIN price_limit_config p ON p.stock_type = s.stock_type " +
                     "WHERE s.stock_code = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, new BigDecimal("0.10"), stockCode);
        
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        BigDecimal previousClose = new BigDecimal(row.get("previous_close").toString());
        BigDecimal limitRate = new BigDecimal(row.get("limit_rate").toString());

        Limits limits = new Limits();
        limits.setPreviousClose(previousClose);
        limits.setLimitRate(limitRate);
        limits.setUpperLimit(roundPrice(previousClose.multiply(BigDecimal.ONE.add(limitRate))));
        limits.setLowerLimit(roundPrice(previousClose.multiply(BigDecimal.ONE.subtract(limitRate))));

        limitsCache.put(stockCode, limits);
        return limits;
    }

    public Limits getPriceLimits(String stockCode) {
        if (limitsCache.containsKey(stockCode)) {
            return limitsCache.get(stockCode);
        }
        return loadPriceLimits(stockCode);
    }

    public ValidationResult validateOrderPrice(String stockCode, BigDecimal price) {
        Limits limits = getPriceLimits(stockCode);
        if (limits == null) {
            return new ValidationResult(false, "股票 " + stockCode + " 不存在", null, null);
        }

        if (price.compareTo(limits.getUpperLimit()) > 0) {
            return new ValidationResult(false, "委托价格 " + price + " 超过涨停价 " + limits.getUpperLimit(), limits.getUpperLimit(), limits.getLowerLimit());
        }

        if (price.compareTo(limits.getLowerLimit()) < 0) {
            return new ValidationResult(false, "委托价格 " + price + " 低于跌停价 " + limits.getLowerLimit(), limits.getUpperLimit(), limits.getLowerLimit());
        }

        return new ValidationResult(true, null, limits.getUpperLimit(), limits.getLowerLimit());
    }

    public BigDecimal clampTradePrice(String stockCode, BigDecimal rawPrice) {
        Limits limits = getPriceLimits(stockCode);
        if (limits == null) return roundPrice(rawPrice);

        if (rawPrice.compareTo(limits.getUpperLimit()) > 0) {
            log.info("成交价 {} 被钳制到涨停价 {} ({})", rawPrice, limits.getUpperLimit(), stockCode);
            return limits.getUpperLimit();
        }
        if (rawPrice.compareTo(limits.getLowerLimit()) < 0) {
            log.info("成交价 {} 被钳制到跌停价 {} ({})", rawPrice, limits.getLowerLimit(), stockCode);
            return limits.getLowerLimit();
        }
        return roundPrice(rawPrice);
    }

    public void refreshAllLimits() {
        String sql = "SELECT s.stock_code, s.stock_type, s.previous_close, " +
                     "COALESCE(p.limit_rate, ?) AS limit_rate " +
                     "FROM stock_info s " +
                     "LEFT JOIN price_limit_config p ON p.stock_type = s.stock_type";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, new BigDecimal("0.10"));

        limitsCache.clear();
        for (Map<String, Object> row : rows) {
            String stockCode = row.get("stock_code").toString();
            BigDecimal previousClose = new BigDecimal(row.get("previous_close").toString());
            BigDecimal limitRate = new BigDecimal(row.get("limit_rate").toString());

            Limits limits = new Limits();
            limits.setPreviousClose(previousClose);
            limits.setLimitRate(limitRate);
            limits.setUpperLimit(roundPrice(previousClose.multiply(BigDecimal.ONE.add(limitRate))));
            limits.setLowerLimit(roundPrice(previousClose.multiply(BigDecimal.ONE.subtract(limitRate))));
            limitsCache.put(stockCode, limits);
        }
        log.info("涨跌停缓存已刷新，共 {} 只股票", limitsCache.size());
    }

    public void updateLimitRate(String stockType, BigDecimal newRate) {
        String sql = "INSERT INTO price_limit_config (stock_type, limit_rate) VALUES (?, ?) " +
                     "ON DUPLICATE KEY UPDATE limit_rate = ?";
        jdbcTemplate.update(sql, stockType, newRate, newRate);
        log.info("涨跌停幅度已更新: {} = {}%", stockType, newRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP));
    }

    /**
     * 直接存入涨跌停缓存（供测试用，绕过数据库查询）。
     */
    void putLimits(String stockCode, Limits limits) {
        limitsCache.put(stockCode, limits);
    }

    private BigDecimal roundPrice(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
