package com.trading.central.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PriceLimiterTest {

    private PriceLimiter limiter;

    @BeforeEach
    void setUp() {
        // JdbcTemplate mock 仅用于构造 PriceLimiter，不被实际调用
        JdbcTemplate mockJdbc = mock(JdbcTemplate.class);
        limiter = new PriceLimiter(mockJdbc);
    }

    // ================================================================
    // 涨跌停验价
    // ================================================================

    @Test
    void testNormalStockPriceLimits() {
        // 前收盘 10.00，NORMAL ±10%
        putLimits("600519", bd("10.00"), bd("0.1000"));

        // 涨停价 = 10.00 * 1.10 = 11.00
        // 跌停价 = 10.00 * 0.90 = 9.00

        // 价格在区间内
        PriceLimiter.ValidationResult r = limiter.validateOrderPrice("600519", bd("10.50"));
        assertTrue(r.valid);
        assertNull(r.reason);

        // 价格等于涨停价（允许）
        r = limiter.validateOrderPrice("600519", bd("11.00"));
        assertTrue(r.valid);

        // 价格等于跌停价（允许）
        r = limiter.validateOrderPrice("600519", bd("9.00"));
        assertTrue(r.valid);

        // 超过涨停价
        r = limiter.validateOrderPrice("600519", bd("11.01"));
        assertFalse(r.valid);
        assertTrue(r.reason.contains("涨停"));

        // 低于跌停价
        r = limiter.validateOrderPrice("600519", bd("8.99"));
        assertFalse(r.valid);
        assertTrue(r.reason.contains("跌停"));
    }

    @Test
    void testSTStockPriceLimits() {
        // 前收盘 10.00，ST ±5%
        putLimits("000002", bd("10.00"), bd("0.0500"));

        // 涨停价 = 10.00 * 1.05 = 10.50
        // 跌停价 = 10.00 * 0.95 = 9.50

        PriceLimiter.ValidationResult r = limiter.validateOrderPrice("000002", bd("10.00"));
        assertTrue(r.valid);

        r = limiter.validateOrderPrice("000002", bd("10.60"));
        assertFalse(r.valid);
        assertTrue(r.reason.contains("涨停"));

        r = limiter.validateOrderPrice("000002", bd("9.40"));
        assertFalse(r.valid);
        assertTrue(r.reason.contains("跌停"));
    }

    @Test
    void testNonExistentStock() {
        // 不预填缓存 → getPriceLimits 返回 null → 股票不存在
        PriceLimiter.ValidationResult r = limiter.validateOrderPrice("UNKNOWN", bd("10.00"));
        assertFalse(r.valid);
        assertTrue(r.reason.contains("不存在"));
    }

    // ================================================================
    // 成交价钳制
    // ================================================================

    @Test
    void testClampTradePrice_withinLimits() {
        putLimits("600519", bd("10.00"), bd("0.1000"));

        // 成交价 10.50 在 9.00~11.00 之间，不钳制
        BigDecimal clamped = limiter.clampTradePrice("600519", bd("10.50"));
        assertEqual(bd("10.50"), clamped);
    }

    @Test
    void testClampTradePrice_aboveUpperLimit() {
        putLimits("600519", bd("10.00"), bd("0.1000"));

        // 涨停 11.00，成交价 12.50 应钳制到 11.00
        BigDecimal clamped = limiter.clampTradePrice("600519", bd("12.50"));
        assertEqual(bd("11.00"), clamped);
    }

    @Test
    void testClampTradePrice_belowLowerLimit() {
        putLimits("600519", bd("10.00"), bd("0.1000"));

        // 跌停 9.00，成交价 8.50 应钳制到 9.00
        BigDecimal clamped = limiter.clampTradePrice("600519", bd("8.50"));
        assertEqual(bd("9.00"), clamped);
    }

    @Test
    void testClampTradePrice_atLimit_exact() {
        putLimits("600519", bd("10.00"), bd("0.1000"));

        // 正好等于涨停价，不变
        BigDecimal clamped = limiter.clampTradePrice("600519", bd("11.00"));
        assertEqual(bd("11.00"), clamped);
    }

    @Test
    void testClampTradePrice_ST_stock() {
        putLimits("000002", bd("25.60"), bd("0.0500"));

        // 涨停 26.88, 跌停 24.32
        // 成交价 27.50 应钳制到 26.88
        BigDecimal clamped = limiter.clampTradePrice("000002", bd("27.50"));
        assertEqual(bd("26.88"), clamped);
    }

    // ================================================================
    // 缓存
    // ================================================================

    @Test
    void testLimitsAreCached() {
        putLimits("600519", bd("100.00"), bd("0.1000"));

        // 第一次调用从缓存获取
        PriceLimiter.Limits limits1 = limiter.getPriceLimits("600519");
        assertNotNull(limits1);

        // 第二次调用应返回同一对象（缓存命中）
        PriceLimiter.Limits limits2 = limiter.getPriceLimits("600519");
        assertSame(limits1, limits2);
    }

    @Test
    void testClampTradePrice_unknownStock() {
        // 未设置涨跌停 → 不钳制，原样返回
        BigDecimal clamped = limiter.clampTradePrice("UNKNOWN", bd("99.99"));
        assertEqual(bd("99.99"), clamped);
    }

    // ================================================================
    // 辅助
    // ================================================================

    /**
     * 构造一个 Limits 并直接放入 PriceLimiter 缓存，绕过数据库查询。
     */
    private void putLimits(String stockCode, BigDecimal previousClose, BigDecimal limitRate) {
        PriceLimiter.Limits limits = new PriceLimiter.Limits();
        limits.setPreviousClose(previousClose);
        limits.setLimitRate(limitRate);
        limits.setUpperLimit(previousClose.multiply(BigDecimal.ONE.add(limitRate)).setScale(2, java.math.RoundingMode.HALF_UP));
        limits.setLowerLimit(previousClose.multiply(BigDecimal.ONE.subtract(limitRate)).setScale(2, java.math.RoundingMode.HALF_UP));
        limiter.putLimits(stockCode, limits);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void assertEqual(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                "Expected " + expected + " but got " + actual);
    }
}
