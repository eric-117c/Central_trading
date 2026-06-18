package com.trading.central.engine;

import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集合竞价单元测试。
 *
 * 覆盖 CallAuctionBook 的三大核心方法：
 * 1. determineAuctionPrice — 最大成交量价格发现
 * 2. matchAtPrice — 按时间优先撮合
 * 3. drainRemainingOrders — 剩余订单清理
 */
class CallAuctionTest {

    private CallAuctionBook book;
    private PriceLimiter mockLimiter;

    @BeforeEach
    void setUp() {
        book = new CallAuctionBook("600519");
        mockLimiter = mock(PriceLimiter.class);
        // 不做涨跌停钳制
        when(mockLimiter.clampTradePrice(anyString(), any(BigDecimal.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private OrderEntry buy(String id, BigDecimal price, int qty) {
        OrderEntry o = new OrderEntry();
        o.setOrderId(id);
        o.setAccountId("ACC1");
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

    private OrderEntry sell(String id, BigDecimal price, int qty) {
        OrderEntry o = new OrderEntry();
        o.setOrderId(id);
        o.setAccountId("ACC2");
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

    private OrderEntry buyWithTime(String id, BigDecimal price, int qty, LocalDateTime t) {
        OrderEntry o = buy(id, price, qty);
        o.setEntryTime(t);
        return o;
    }

    private OrderEntry sellWithTime(String id, BigDecimal price, int qty, LocalDateTime t) {
        OrderEntry o = sell(id, price, qty);
        o.setEntryTime(t);
        return o;
    }

    // ================================================================
    // determineAuctionPrice — 最大成交量价格发现
    // ================================================================

    @Test
    void testDetermineAuctionPrice_maxVolume() {
        // 需求文档中的示例:
        // 前收盘 = 10.00
        // 买单: 10.50×1000, 10.30×2000, 10.00×3000
        // 卖单: 9.90×2000, 10.10×1500, 10.40×1000
        book.addOrder(buy("B1", bd("10.50"), 1000));
        book.addOrder(buy("B2", bd("10.30"), 2000));
        book.addOrder(buy("B3", bd("10.00"), 3000));
        book.addOrder(sell("S1", bd("9.90"), 2000));
        book.addOrder(sell("S2", bd("10.10"), 1500));
        book.addOrder(sell("S3", bd("10.40"), 1000));

        BigDecimal price = book.determineAuctionPrice(bd("10.00"), mockLimiter);

        // 最大成交量 2500，平局价 10.10 和 10.30，选最接近 10.00 的 → 10.10
        assertNotNull(price);
        assertEqual(bd("10.10"), price);
    }

    @Test
    void testDetermineAuctionPrice_tiebreakerClosestToPreviousClose() {
        // 构造一个平局场景：
        // 前收盘 = 10.00
        // 买单: 11.00×100, 10.00×200
        // 卖单: 9.00×100, 10.00×200
        //
        // P=11.00: cumBuy=100, cumSell=300, matchVol=100
        // P=10.00: cumBuy=300, cumSell=300, matchVol=300 ✓ max
        // P=9.00:  cumBuy=300, cumSell=100, matchVol=100
        // → 唯一最大 = 10.00
        book.addOrder(buy("B1", bd("11.00"), 100));
        book.addOrder(buy("B2", bd("10.00"), 200));
        book.addOrder(sell("S1", bd("9.00"), 100));
        book.addOrder(sell("S2", bd("10.00"), 200));

        BigDecimal price = book.determineAuctionPrice(bd("10.00"), mockLimiter);
        assertNotNull(price);
        assertEqual(bd("10.00"), price);
    }

    @Test
    void testDetermineAuctionPrice_singleCandidate() {
        // 仅一种价格交叉: 买单 11.00×100, 卖单 10.00×100
        // 候选价: 10.00, 10.50(前收盘), 11.00 → 全部 matchVol=100
        // 平局规则: 选最接近前收盘 10.50 → 10.50 本身 (距离 0)
        book.addOrder(buy("B1", bd("11.00"), 100));
        book.addOrder(sell("S1", bd("10.00"), 100));

        BigDecimal price = book.determineAuctionPrice(bd("10.50"), mockLimiter);
        assertNotNull(price);
        assertEqual(bd("10.50"), price);
    }

    @Test
    void testDetermineAuctionPrice_emptyBook() {
        assertNull(book.determineAuctionPrice(bd("10.00"), mockLimiter));
    }

    @Test
    void testDetermineAuctionPrice_oneSidedBook() {
        // 只有买单，无卖单 → 无法撮合
        book.addOrder(buy("B1", bd("10.00"), 100));
        assertNull(book.determineAuctionPrice(bd("10.00"), mockLimiter));
    }

    @Test
    void testDetermineAuctionPrice_noPriceOverlap() {
        // 买单最高 9.00，卖单最低 10.00 → 无交叉
        book.addOrder(buy("B1", bd("9.00"), 100));
        book.addOrder(sell("S1", bd("10.00"), 100));
        // matchVol=0 at all prices
        assertNull(book.determineAuctionPrice(bd("10.00"), mockLimiter));
    }

    @Test
    void testIsEmpty() {
        assertTrue(book.isEmpty()); // 两边都为空 → isEmpty=true（特殊处理）

        book.addOrder(buy("B1", bd("10.00"), 100));
        assertTrue(book.isEmpty()); // 只有买单无卖单 → isEmpty=true（无法撮合）

        book.addOrder(sell("S1", bd("10.00"), 100));
        assertFalse(book.isEmpty()); // 两边都有 → 可以撮合
    }

    // ================================================================
    // matchAtPrice — 按时间优先撮合
    // ================================================================

    @Test
    void testMatchAtPrice_timePriority() {
        LocalDateTime base = LocalDateTime.of(2026, 6, 18, 9, 15, 0);

        // 买单: B1 10.50×1000(t=0s), B2 10.30×2000(t=10s)
        book.addOrder(buyWithTime("B1", bd("10.50"), 1000, base));
        book.addOrder(buyWithTime("B2", bd("10.30"), 2000, base.plusSeconds(10)));

        // 卖单: S1 9.90×2000(t=5s), S2 10.10×1500(t=15s)
        book.addOrder(sellWithTime("S1", bd("9.90"), 2000, base.plusSeconds(5)));
        book.addOrder(sellWithTime("S2", bd("10.10"), 1500, base.plusSeconds(15)));

        // 拍卖价 10.10（上面测试已验证）
        List<OrderBook.MatchAtom> trades = book.matchAtPrice(bd("10.10"));

        // 符合条件: 买 B1(10.50) B2(10.30); 卖 S1(9.90) S2(10.10)
        // 时间排序: 买 B1(0), B2(10); 卖 S1(5), S2(15)
        // 配对: B1(t=0) vs S1(t=5) → 1000股 (B1 done)
        //       B2(t=10) vs S1(剩余1000) → 1000股 (S1 done)
        //       B2(剩余1000) vs S2(t=15, 1500) → 1000股 (B2 done)
        assertEquals(3, trades.size());

        // 第一笔: B1 vs S1, 1000股
        assertEquals("B1", trades.get(0).buyOrder.getOrderId());
        assertEquals("S1", trades.get(0).sellOrder.getOrderId());
        assertEquals(1000, trades.get(0).tradeQty);

        // 第二笔: B2 vs S1, 1000股
        assertEquals("B2", trades.get(1).buyOrder.getOrderId());
        assertEquals("S1", trades.get(1).sellOrder.getOrderId());
        assertEquals(1000, trades.get(1).tradeQty);

        // 第三笔: B2 vs S2, 1000股
        assertEquals("B2", trades.get(2).buyOrder.getOrderId());
        assertEquals("S2", trades.get(2).sellOrder.getOrderId());
        assertEquals(1000, trades.get(2).tradeQty);

        // 验证成交价均为拍卖价
        for (OrderBook.MatchAtom t : trades) {
            assertEqual(bd("10.10"), t.tradePrice);
        }
    }

    @Test
    void testMatchAtPrice_allTradesAtAuctionPrice() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("12.00"), 100));
        book.addOrder(sell("S1", bd("10.00"), 100));

        // 拍卖价 11.00（类似连续竞价的中间价），但集合竞价用拍卖价
        List<OrderBook.MatchAtom> trades = book.matchAtPrice(bd("11.00"));
        assertEquals(1, trades.size());
        assertEqual(bd("11.00"), trades.get(0).tradePrice);
        // 注意：集合竞价的成交价是拍卖价，不是 (12+10)/2=11
    }

    @Test
    void testMatchAtPrice_excludesIneligibleOrders() {
        LocalDateTime t = LocalDateTime.now();

        // 买单: B1 11.00(合格), B2 9.50(不合格)
        book.addOrder(buy("B1", bd("11.00"), 100));
        book.addOrder(buy("B2", bd("9.50"), 100));

        // 卖单: S1 9.80(合格), S2 10.50(不合格)
        book.addOrder(sell("S1", bd("9.80"), 100));
        book.addOrder(sell("S2", bd("10.50"), 100));

        // 拍卖价 10.00
        // 合格买: B1(11.00 >= 10.00)
        // 合格卖: S1(9.80 <= 10.00)
        List<OrderBook.MatchAtom> trades = book.matchAtPrice(bd("10.00"));

        assertEquals(1, trades.size());
        assertEquals("B1", trades.get(0).buyOrder.getOrderId());
        assertEquals("S1", trades.get(0).sellOrder.getOrderId());
    }

    // ================================================================
    // drainRemainingOrders
    // ================================================================

    @Test
    void testDrainRemainingOrders() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("11.00"), 200));  // 将完全成交100
        book.addOrder(sell("S1", bd("9.00"), 100));  // 将完全成交

        // 先撮合
        book.matchAtPrice(bd("10.00"));  // B1 vs S1: 100股成交，B1 剩余 100

        // 获取剩余订单
        List<OrderEntry> remaining = book.drainRemainingOrders();
        assertEquals(1, remaining.size());
        assertEquals("B1", remaining.get(0).getOrderId());
        assertEquals(100, remaining.get(0).getRemainingQuantity());
        assertEquals(OrderStatus.PART_TRADED.name(), remaining.get(0).getStatus());

        // 订单簿已清空
        assertTrue(book.getAllBuyOrders().isEmpty());
        assertTrue(book.getAllSellOrders().isEmpty());
    }

    @Test
    void testDrainRemainingOrders_allFullyTraded() {
        LocalDateTime t = LocalDateTime.now();
        book.addOrder(buy("B1", bd("11.00"), 100));
        book.addOrder(sell("S1", bd("9.00"), 100));

        book.matchAtPrice(bd("10.00")); // 完全成交

        List<OrderEntry> remaining = book.drainRemainingOrders();
        assertTrue(remaining.isEmpty()); // TRADED 状态的订单不会被返回
    }

    // ================================================================
    // removeOrder
    // ================================================================

    @Test
    void testRemoveOrder_fromCallAuctionBook() {
        book.addOrder(buy("B1", bd("10.00"), 100));
        book.addOrder(sell("S1", bd("10.00"), 100));

        OrderEntry removed = book.removeOrder("B1");
        assertNotNull(removed);
        assertEquals("B1", removed.getOrderId());

        // 撤单后只剩卖单，无法撮合
        assertTrue(book.isEmpty());
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
