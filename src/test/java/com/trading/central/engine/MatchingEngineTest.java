package com.trading.central.engine;

import com.trading.central.dashboard.TradingEventBroadcaster;
import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MatchingEngine 连续竞价单元测试。
 *
 * PriceLimiter 使用 Mockito mock，默认不做钳制（原样返回成交价）。
 */
class MatchingEngineTest {

    private MatchingEngine engine;
    private PriceLimiter mockLimiter;
    private TradingEventBroadcaster mockBroadcaster;

    @BeforeEach
    void setUp() {
        mockLimiter = mock(PriceLimiter.class);
        mockBroadcaster = mock(TradingEventBroadcaster.class);
        // 默认：不做涨跌停钳制
        when(mockLimiter.clampTradePrice(anyString(), any(BigDecimal.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        engine = new MatchingEngine(mockLimiter, mockBroadcaster);
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private OrderEntry newBuy(String orderId, BigDecimal price, int qty) {
        OrderEntry o = new OrderEntry();
        o.setOrderId(orderId);
        o.setAccountId("ACC001");
        o.setStockCode("600519");
        o.setSide(Side.BUY.name());
        o.setPrice(price);
        o.setQuantity(qty);
        o.setFilledQuantity(0);
        o.setRemainingQuantity(qty);
        o.setStatus(OrderStatus.ACCEPTED.name());
        o.setEntryTime(LocalDateTime.now());
        return o;
    }

    private OrderEntry newSell(String orderId, BigDecimal price, int qty) {
        OrderEntry o = new OrderEntry();
        o.setOrderId(orderId);
        o.setAccountId("ACC002");
        o.setStockCode("600519");
        o.setSide(Side.SELL.name());
        o.setPrice(price);
        o.setQuantity(qty);
        o.setFilledQuantity(0);
        o.setRemainingQuantity(qty);
        o.setStatus(OrderStatus.ACCEPTED.name());
        o.setEntryTime(LocalDateTime.now());
        return o;
    }

    private OrderEntry newBuyWithTime(String orderId, BigDecimal price, int qty, LocalDateTime time) {
        OrderEntry o = newBuy(orderId, price, qty);
        o.setEntryTime(time);
        return o;
    }

    private OrderEntry newSellWithTime(String orderId, BigDecimal price, int qty, LocalDateTime time) {
        OrderEntry o = newSell(orderId, price, qty);
        o.setEntryTime(time);
        return o;
    }

    /** 记录每笔成交的简单回调 */
    private static class TradeRecorder implements MatchingEngine.TradeCallback {
        final List<MatchingEngine.TradeInfo> trades = new ArrayList<>();
        @Override
        public void onTrade(OrderEntry buy, OrderEntry sell, BigDecimal price, int qty) {
            trades.add(new MatchingEngine.TradeInfo(buy, sell, price, qty));
        }
    }

    // ================================================================
    // 测试用例
    // ================================================================

    @Test
    void testSamePriceImmediateTrade() throws Exception {
        // 先挂卖单 10.00×100
        engine.matchOrder(newSell("S1", bd("10.00"), 100), null);

        // 再挂买单 10.00×100
        TradeRecorder recorder = new TradeRecorder();
        MatchingEngine.MatchResult result = engine.matchOrder(newBuy("B1", bd("10.00"), 100), recorder);

        assertEquals(OrderStatus.TRADED.name(), result.finalStatus);
        assertEquals(1, recorder.trades.size());
        MatchingEngine.TradeInfo t = recorder.trades.get(0);
        assertEqual(bd("10.00"), t.tradePrice);
        assertEquals(100, t.tradeQty);
        assertEquals("B1", t.buyOrder.getOrderId());
        assertEquals("S1", t.sellOrder.getOrderId());
    }

    @Test
    void testBuyPriceHigherThanLowestSell() throws Exception {
        // 先挂卖单 12.00×100
        engine.matchOrder(newSell("S1", bd("12.00"), 100), null);

        // 挂买单 13.00×100 → 中间价 (13+12)/2 = 12.50
        TradeRecorder recorder = new TradeRecorder();
        MatchingEngine.MatchResult result = engine.matchOrder(newBuy("B1", bd("13.00"), 100), recorder);

        assertEquals(OrderStatus.TRADED.name(), result.finalStatus);
        assertEquals(1, recorder.trades.size());
        assertEqual(bd("12.50"), recorder.trades.get(0).tradePrice);
    }

    @Test
    void testSellPriceLowerThanHighestBuy() throws Exception {
        // 先挂买单 12.00×100
        engine.matchOrder(newBuy("B1", bd("12.00"), 100), null);

        // 挂卖单 11.00×100 → 中间价 (12+11)/2 = 11.50
        TradeRecorder recorder = new TradeRecorder();
        MatchingEngine.MatchResult result = engine.matchOrder(newSell("S1", bd("11.00"), 100), recorder);

        assertEquals(OrderStatus.TRADED.name(), result.finalStatus);
        assertEquals(1, recorder.trades.size());
        assertEqual(bd("11.50"), recorder.trades.get(0).tradePrice);
    }

    @Test
    void testBuyPriceLowerThanLowestSell_noTrade() throws Exception {
        // 先挂卖单 12.00×100
        engine.matchOrder(newSell("S1", bd("12.00"), 100), null);

        // 挂买单 11.00×100 → 价格不匹配，挂单等待
        TradeRecorder recorder = new TradeRecorder();
        OrderEntry newOrder = newBuy("B1", bd("11.00"), 100);
        MatchingEngine.MatchResult result = engine.matchOrder(newOrder, recorder);

        assertEquals(OrderStatus.ACCEPTED.name(), result.finalStatus);
        assertTrue(recorder.trades.isEmpty());
        // 验证买单已挂入订单簿
        assertEquals(bd("11.00"), engine.getTopPrices("600519").get("bidPrice"));
    }

    @Test
    void testSellPriceHigherThanHighestBuy_noTrade() throws Exception {
        // 先挂买单 10.00×100
        engine.matchOrder(newBuy("B1", bd("10.00"), 100), null);

        // 挂卖单 11.00×100 → 价格不匹配
        TradeRecorder recorder = new TradeRecorder();
        OrderEntry newOrder = newSell("S1", bd("11.00"), 100);
        MatchingEngine.MatchResult result = engine.matchOrder(newOrder, recorder);

        assertEquals(OrderStatus.ACCEPTED.name(), result.finalStatus);
        assertTrue(recorder.trades.isEmpty());
    }

    @Test
    void testMultiFill() throws Exception {
        // 先挂两笔卖单: 12.00×1500, 12.20×1300
        engine.matchOrder(newSell("S1", bd("12.00"), 1500), null);
        engine.matchOrder(newSell("S2", bd("12.20"), 1300), null);

        // 挂买单 13.00×2000
        TradeRecorder recorder = new TradeRecorder();
        MatchingEngine.MatchResult result = engine.matchOrder(newBuy("B1", bd("13.00"), 2000), recorder);

        assertEquals(OrderStatus.TRADED.name(), result.finalStatus);
        assertEquals(2, recorder.trades.size());

        // 第1笔: (13+12)/2 = 12.50, 1500股
        assertEqual(bd("12.50"), recorder.trades.get(0).tradePrice);
        assertEquals(1500, recorder.trades.get(0).tradeQty);

        // 第2笔: (13+12.20)/2 = 12.60, 500股
        assertEqual(bd("12.60"), recorder.trades.get(1).tradePrice);
        assertEquals(500, recorder.trades.get(1).tradeQty);

        // 验证加权均价: (12.50*1500 + 12.60*500) / 2000 = 12.525 → 12.53
        BigDecimal weighted = bd("12.50").multiply(bd("1500"))
                .add(bd("12.60").multiply(bd("500")))
                .divide(bd("2000"), 2, java.math.RoundingMode.HALF_UP);
        assertEqual(bd("12.53"), weighted);
    }

    @Test
    void testPartialFill() throws Exception {
        // 先挂卖单 10.00×50
        engine.matchOrder(newSell("S1", bd("10.00"), 50), null);

        // 挂买单 10.00×100 → 只能成交50
        OrderEntry newOrder = newBuy("B1", bd("10.00"), 100);
        TradeRecorder recorder = new TradeRecorder();
        MatchingEngine.MatchResult result = engine.matchOrder(newOrder, recorder);

        assertEquals(OrderStatus.PART_TRADED.name(), result.finalStatus);
        assertEquals(1, recorder.trades.size());
        assertEquals(50, recorder.trades.get(0).tradeQty);
        assertEquals(50, newOrder.getRemainingQuantity());
        assertEquals(50, newOrder.getFilledQuantity());
    }

    @Test
    void testPriceLimitClamping() throws Exception {
        // 模拟涨停价 12.30 钳制
        when(mockLimiter.clampTradePrice(eq("600519"), any(BigDecimal.class)))
                .thenReturn(bd("12.30"));

        // 先挂卖单 12.00×100
        engine.matchOrder(newSell("S1", bd("12.00"), 100), null);

        // 挂买单 13.00×100 → 中间价 12.50 被钳制到 12.30
        TradeRecorder recorder = new TradeRecorder();
        MatchingEngine.MatchResult result = engine.matchOrder(newBuy("B1", bd("13.00"), 100), recorder);

        assertEquals(OrderStatus.TRADED.name(), result.finalStatus);
        assertEquals(1, recorder.trades.size());
        assertEqual(bd("12.30"), recorder.trades.get(0).tradePrice);
    }

    @Test
    void testTimePriority_samePrice() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 6, 18, 9, 30, 0);

        // 挂两笔同价卖单，时间不同
        engine.matchOrder(newSellWithTime("S1", bd("10.00"), 100, base), null);
        engine.matchOrder(newSellWithTime("S2", bd("10.00"), 100, base.plusSeconds(10)), null);

        // 挂买单 10.00×150 → 应先匹配 S1（时间早），再匹配 S2
        TradeRecorder recorder = new TradeRecorder();
        engine.matchOrder(newBuy("B1", bd("10.00"), 150), recorder);

        assertEquals(2, recorder.trades.size());
        assertEquals("S1", recorder.trades.get(0).sellOrder.getOrderId()); // 第一笔对 S1
        assertEquals("S2", recorder.trades.get(1).sellOrder.getOrderId()); // 第二笔对 S2
        assertEquals(100, recorder.trades.get(0).tradeQty);
        assertEquals(50, recorder.trades.get(1).tradeQty);
    }

    @Test
    void testPricePriority_differentPrice() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 6, 18, 9, 30, 0);

        // 买单: B1 10.50（后到）, B2 11.00（先到）
        // 价格优先: B2(11.00) > B1(10.50), 时间优先只对同价生效
        engine.matchOrder(newBuyWithTime("B1", bd("10.50"), 100, base.plusSeconds(10)), null);
        engine.matchOrder(newBuyWithTime("B2", bd("11.00"), 100, base), null);

        // 挂卖单 10.00×150 → 应先匹配 B2（价格更优）
        TradeRecorder recorder = new TradeRecorder();
        engine.matchOrder(newSell("S1", bd("10.00"), 150), recorder);

        assertEquals(2, recorder.trades.size());
        assertEquals("B2", recorder.trades.get(0).buyOrder.getOrderId()); // 价格优先: 11.00 > 10.50
        assertEquals("B1", recorder.trades.get(1).buyOrder.getOrderId());
        assertEqual(bd("10.50"), recorder.trades.get(0).tradePrice); // (11+10)/2
        assertEqual(bd("10.25"), recorder.trades.get(1).tradePrice); // (10.50+10)/2
    }

    @Test
    void testEmptyBook_orderAdded() throws Exception {
        TradeRecorder recorder = new TradeRecorder();
        OrderEntry newOrder = newBuy("B1", bd("10.00"), 100);
        MatchingEngine.MatchResult result = engine.matchOrder(newOrder, recorder);

        assertEquals(OrderStatus.ACCEPTED.name(), result.finalStatus);
        assertTrue(recorder.trades.isEmpty());
        // 确认已挂入订单簿
        assertEquals(bd("10.00"), engine.getTopPrices("600519").get("bidPrice"));
    }

    @Test
    void testCancelOrder() throws Exception {
        engine.matchOrder(newBuy("B1", bd("10.00"), 100), null);

        OrderEntry removed = engine.cancelOrderInBook("B1", "600519");
        assertNotNull(removed);
        assertEquals("B1", removed.getOrderId());

        // 确认已移除
        assertNull(engine.getTopPrices("600519").get("bidPrice"));
    }

    @Test
    void testConcurrentMatching_noDuplicateTrades() throws Exception {
        // 先挂一笔卖单
        engine.matchOrder(newSell("S1", bd("10.00"), 500), null);

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger totalTrades = new AtomicInteger(0);
        AtomicInteger totalTradeQty = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    TradeRecorder recorder = new TradeRecorder();
                    engine.matchOrder(newBuy("B" + idx, bd("10.00"), 150), recorder);
                    totalTrades.addAndGet(recorder.trades.size());
                    for (MatchingEngine.TradeInfo t : recorder.trades) {
                        totalTradeQty.addAndGet(t.tradeQty);
                    }
                } catch (Exception e) {
                    fail(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 总成交量不应超过卖单总量 500
        assertTrue(totalTradeQty.get() <= 500,
                "总成交量 " + totalTradeQty.get() + " 不应超过对手方总量 500");
    }

    // ================================================================
    // 辅助
    // ================================================================

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void assertEqual(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                "Expected " + expected + " but got " + actual);
    }
}
