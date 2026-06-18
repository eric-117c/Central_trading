package com.trading.central.engine;

import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderBookTest {

    private OrderBook book;
    private PriceLimiter mockLimiter;

    @BeforeEach
    void setUp() {
        book = new OrderBook("600519");
        mockLimiter = mock(PriceLimiter.class);
        // 不做涨跌停钳制
        when(mockLimiter.clampTradePrice(anyString(), any(BigDecimal.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private OrderEntry buy(String id, BigDecimal price, int qty, LocalDateTime time) {
        OrderEntry o = new OrderEntry();
        o.setOrderId(id);
        o.setSide(Side.BUY.name());
        o.setPrice(price);
        o.setQuantity(qty);
        o.setRemainingQuantity(qty);
        o.setFilledQuantity(0);
        o.setStatus(OrderStatus.ACCEPTED.name());
        o.setEntryTime(time);
        o.setStockCode("600519");
        o.setAccountId("ACC1");
        return o;
    }

    private OrderEntry sell(String id, BigDecimal price, int qty, LocalDateTime time) {
        OrderEntry o = new OrderEntry();
        o.setOrderId(id);
        o.setSide(Side.SELL.name());
        o.setPrice(price);
        o.setQuantity(qty);
        o.setRemainingQuantity(qty);
        o.setFilledQuantity(0);
        o.setStatus(OrderStatus.ACCEPTED.name());
        o.setEntryTime(time);
        o.setStockCode("600519");
        o.setAccountId("ACC2");
        return o;
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // ================================================================
    // 排序验证
    // ================================================================

    @Test
    void testBuyOrdersSortedByPriceDesc() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("10.00"), 100, t));
        book.addOrder(buy("B2", bd("11.00"), 100, t));
        book.addOrder(buy("B3", bd("10.50"), 100, t));

        List<OrderEntry> buys = book.getAllBuyOrders();
        assertEquals(3, buys.size());
        // 价格降序: 11.00, 10.50, 10.00
        assertEqual(bd("11.00"), buys.get(0).getPrice());
        assertEqual(bd("10.50"), buys.get(1).getPrice());
        assertEqual(bd("10.00"), buys.get(2).getPrice());
    }

    @Test
    void testSellOrdersSortedByPriceAsc() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(sell("S1", bd("12.00"), 100, t));
        book.addOrder(sell("S2", bd("11.00"), 100, t));
        book.addOrder(sell("S3", bd("11.50"), 100, t));

        List<OrderEntry> sells = book.getAllSellOrders();
        assertEquals(3, sells.size());
        // 价格升序: 11.00, 11.50, 12.00
        assertEqual(bd("11.00"), sells.get(0).getPrice());
        assertEqual(bd("11.50"), sells.get(1).getPrice());
        assertEqual(bd("12.00"), sells.get(2).getPrice());
    }

    @Test
    void testSamePrice_timePriority() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 18, 9, 30, 0);
        book.addOrder(buy("B1", bd("10.00"), 100, base.plusSeconds(30)));
        book.addOrder(buy("B2", bd("10.00"), 100, base));
        book.addOrder(buy("B3", bd("10.00"), 100, base.plusSeconds(10)));

        List<OrderEntry> buys = book.getAllBuyOrders();
        // 同价 10.00，时间升序: B2(0s), B3(10s), B1(30s)
        assertEquals("B2", buys.get(0).getOrderId());
        assertEquals("B3", buys.get(1).getOrderId());
        assertEquals("B1", buys.get(2).getOrderId());
    }

    // ================================================================
    // 基础操作
    // ================================================================

    @Test
    void testAddAndRemove() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("10.00"), 100, t));
        book.addOrder(sell("S1", bd("12.00"), 200, t));

        assertEquals(1, (Integer) book.getStats().get("buyCount"));
        assertEquals(1, (Integer) book.getStats().get("sellCount"));

        OrderEntry removed = book.removeOrder("B1");
        assertNotNull(removed);
        assertEquals("B1", removed.getOrderId());
        assertEquals(0, (Integer) book.getStats().get("buyCount"));

        // 重复删除应返回 null
        assertNull(book.removeOrder("B1"));
    }

    @Test
    void testGetTopPrices() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("10.00"), 100, t));
        book.addOrder(buy("B2", bd("10.50"), 100, t));
        book.addOrder(sell("S1", bd("11.00"), 100, t));
        book.addOrder(sell("S2", bd("11.50"), 100, t));

        assertEqual(bd("10.50"), book.getBidPrice());  // 最高买价
        assertEqual(bd("11.00"), book.getAskPrice());  // 最低卖价
    }

    @Test
    void testGetStats() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("10.00"), 100, t));
        book.addOrder(buy("B2", bd("10.50"), 200, t));
        book.addOrder(sell("S1", bd("12.00"), 150, t));

        var stats = book.getStats();
        assertEquals(2, (Integer) stats.get("buyCount"));
        assertEquals(1, (Integer) stats.get("sellCount"));
        assertEquals(300, (Integer) stats.get("totalBuyQuantity"));
        assertEquals(150, (Integer) stats.get("totalSellQuantity"));
    }

    @Test
    void testClearAll() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("10.00"), 100, t));
        book.addOrder(sell("S1", bd("12.00"), 100, t));

        List<OrderEntry> all = book.clearAll();
        assertEquals(2, all.size());
        assertEquals(0, (Integer) book.getStats().get("buyCount"));
        assertEquals(0, (Integer) book.getStats().get("sellCount"));
    }

    // ================================================================
    // 原子撮合方法
    // ================================================================

    @Test
    void testTryMatchBuy_success() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(sell("S1", bd("10.00"), 100, t));

        OrderEntry newBuy = buy("B1", bd("10.00"), 100, t);
        OrderBook.MatchAtom atom = book.tryMatchBuy(newBuy, mockLimiter);

        assertNotNull(atom);
        assertEqual(bd("10.00"), atom.tradePrice);
        assertEquals(100, atom.tradeQty);
        assertEquals("B1", atom.buyOrder.getOrderId());
        assertEquals("S1", atom.sellOrder.getOrderId());
        assertEquals(OrderStatus.TRADED.name(), newBuy.getStatus());
        assertEquals(OrderStatus.TRADED.name(), atom.sellOrder.getStatus());
        // 对手方已完全成交，应从队列移除
        assertNull(book.getTopSell());
    }

    @Test
    void testTryMatchBuy_noMatch_priceTooLow() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(sell("S1", bd("12.00"), 100, t));

        OrderEntry newBuy = buy("B1", bd("11.00"), 100, t);
        OrderBook.MatchAtom atom = book.tryMatchBuy(newBuy, mockLimiter);

        assertNull(atom); // 买价 < 最低卖价，不匹配
    }

    @Test
    void testTryMatchBuy_noMatch_emptyBook() {
        OrderEntry newBuy = buy("B1", bd("10.00"), 100, LocalDateTime.now());
        OrderBook.MatchAtom atom = book.tryMatchBuy(newBuy, mockLimiter);

        assertNull(atom); // 卖单队列为空
    }

    @Test
    void testTryMatchBuy_partialFill() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(sell("S1", bd("10.00"), 50, t));

        OrderEntry newBuy = buy("B1", bd("10.00"), 100, t);
        OrderBook.MatchAtom atom = book.tryMatchBuy(newBuy, mockLimiter);

        assertNotNull(atom);
        assertEquals(50, atom.tradeQty);
        assertEquals(50, newBuy.getRemainingQuantity());
        assertEquals(OrderStatus.PART_TRADED.name(), newBuy.getStatus());
        assertEquals(OrderStatus.TRADED.name(), atom.sellOrder.getStatus());
        assertNull(book.getTopSell()); // 对手方已成交并从队列移除
    }

    @Test
    void testTryMatchSell_success() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("12.00"), 100, t));

        OrderEntry newSell = sell("S1", bd("12.00"), 100, t);
        OrderBook.MatchAtom atom = book.tryMatchSell(newSell, mockLimiter);

        assertNotNull(atom);
        assertEqual(bd("12.00"), atom.tradePrice);
        assertEquals(100, atom.tradeQty);
        assertEquals("B1", atom.buyOrder.getOrderId());
        assertEquals("S1", atom.sellOrder.getOrderId());
    }

    @Test
    void testTryMatchSell_midPrice() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("13.00"), 100, t));

        OrderEntry newSell = sell("S1", bd("12.00"), 100, t);
        OrderBook.MatchAtom atom = book.tryMatchSell(newSell, mockLimiter);

        assertNotNull(atom);
        assertEqual(bd("12.50"), atom.tradePrice); // (13+12)/2
    }

    @Test
    void testTryMatchSell_noMatch_priceTooHigh() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("10.00"), 100, t));

        OrderEntry newSell = sell("S1", bd("11.00"), 100, t);
        OrderBook.MatchAtom atom = book.tryMatchSell(newSell, mockLimiter);

        assertNull(atom); // 卖价 > 最高买价
    }

    // ================================================================
    // 并发安全
    // ================================================================

    @Test
    void testConcurrentAddOrder() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        LocalDateTime base = LocalDateTime.now();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    book.addOrder(buy("B" + idx, bd("10.00"), 100, base));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, (Integer) book.getStats().get("buyCount"));
        assertEquals(threadCount * 100, (Integer) book.getStats().get("totalBuyQuantity"));
    }

    // ================================================================
    // 辅助
    // ================================================================

    private static void assertEqual(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                "Expected " + expected + " but got " + actual);
    }
}
