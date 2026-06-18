package com.trading.central.engine;

import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 单只股票的订单簿。
 *
 * 买单按价格降序+时间升序排列（价格优先、时间优先）。
 * 卖单按价格升序+时间升序排列（价格优先、时间优先）。
 *
 * 所有公开方法均为 synchronized，保证单股票级别的线程安全。
 * tryMatchBuy / tryMatchSell 将 peek→match→pop 合并为原子操作，消除 TOCTOU 竞态窗口。
 */
public class OrderBook {

    private final String stockCode;

    // 买方：价格降序，同价时间升序
    private final PriorityQueue<OrderEntry> buyOrders;

    // 卖方：价格升序，同价时间升序
    private final PriorityQueue<OrderEntry> sellOrders;

    // O(1) 索引：orderId → OrderEntry，用于快速撤单
    private final Map<String, OrderEntry> orderIndex = new HashMap<>();

    public OrderBook(String stockCode) {
        this.stockCode = stockCode;
        this.buyOrders = new PriorityQueue<>((a, b) -> {
            int priceCmp = b.getPrice().compareTo(a.getPrice());
            if (priceCmp != 0) return priceCmp;
            return a.getEntryTime().compareTo(b.getEntryTime());
        });

        this.sellOrders = new PriorityQueue<>((a, b) -> {
            int priceCmp = a.getPrice().compareTo(b.getPrice());
            if (priceCmp != 0) return priceCmp;
            return a.getEntryTime().compareTo(b.getEntryTime());
        });
    }

    // ================================================================
    // 撮合原子操作（peek → 验价 → 成交价计算 → 数量扣减 → 条件pop）
    // ================================================================

    /**
     * 一次撮合操作的输出。
     */
    public static class MatchAtom {
        public final OrderEntry buyOrder;
        public final OrderEntry sellOrder;
        public final BigDecimal tradePrice;
        public final int tradeQty;

        public MatchAtom(OrderEntry buyOrder, OrderEntry sellOrder, BigDecimal tradePrice, int tradeQty) {
            this.buyOrder = buyOrder;
            this.sellOrder = sellOrder;
            this.tradePrice = tradePrice;
            this.tradeQty = tradeQty;
        }
    }

    /**
     * 尝试将一条新买单与卖单队列顶部撮合。
     * 整个 peek→验价→成交价计算→数量扣减→条件pop 在同一把锁内完成。
     *
     * @param newBuy  新买入委托
     * @param limiter 涨跌停限制器（用于成交价钳制）
     * @return 撮合结果；若对手方为空或价格不匹配则返回 null
     */
    public synchronized MatchAtom tryMatchBuy(OrderEntry newBuy, PriceLimiter limiter) {
        OrderEntry topSell = sellOrders.peek();
        if (topSell == null) return null;

        // 买价 < 最低卖价，价格不匹配
        if (newBuy.getPrice().compareTo(topSell.getPrice()) < 0) return null;

        // 中间价算法：(买价 + 卖价) / 2，钳制到涨跌停
        BigDecimal rawPrice = newBuy.getPrice().add(topSell.getPrice())
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        BigDecimal tradePrice = limiter.clampTradePrice(stockCode, rawPrice);

        int tradeQty = Math.min(newBuy.getRemainingQuantity(), topSell.getRemainingQuantity());

        // 扣减数量
        newBuy.setFilledQuantity(newBuy.getFilledQuantity() + tradeQty);
        newBuy.setRemainingQuantity(newBuy.getRemainingQuantity() - tradeQty);
        topSell.setFilledQuantity(topSell.getFilledQuantity() + tradeQty);
        topSell.setRemainingQuantity(topSell.getRemainingQuantity() - tradeQty);

        // 刷新状态
        refreshStatus(newBuy);
        refreshStatus(topSell);

        // 对手方全部成交 → 从队列移除
        if (topSell.getRemainingQuantity() <= 0) {
            sellOrders.poll();
            orderIndex.remove(topSell.getOrderId());
        }

        return new MatchAtom(newBuy, topSell, tradePrice, tradeQty);
    }

    /**
     * 尝试将一条新卖单与买单队列顶部撮合。
     * 整个 peek→验价→成交价计算→数量扣减→条件pop 在同一把锁内完成。
     *
     * @param newSell 新卖出委托
     * @param limiter 涨跌停限制器（用于成交价钳制）
     * @return 撮合结果；若对手方为空或价格不匹配则返回 null
     */
    public synchronized MatchAtom tryMatchSell(OrderEntry newSell, PriceLimiter limiter) {
        OrderEntry topBuy = buyOrders.peek();
        if (topBuy == null) return null;

        // 卖价 > 最高买价，价格不匹配
        if (newSell.getPrice().compareTo(topBuy.getPrice()) > 0) return null;

        // 中间价算法：(买价 + 卖价) / 2，钳制到涨跌停
        BigDecimal rawPrice = topBuy.getPrice().add(newSell.getPrice())
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        BigDecimal tradePrice = limiter.clampTradePrice(stockCode, rawPrice);

        int tradeQty = Math.min(newSell.getRemainingQuantity(), topBuy.getRemainingQuantity());

        // 扣减数量
        newSell.setFilledQuantity(newSell.getFilledQuantity() + tradeQty);
        newSell.setRemainingQuantity(newSell.getRemainingQuantity() - tradeQty);
        topBuy.setFilledQuantity(topBuy.getFilledQuantity() + tradeQty);
        topBuy.setRemainingQuantity(topBuy.getRemainingQuantity() - tradeQty);

        // 刷新状态
        refreshStatus(newSell);
        refreshStatus(topBuy);

        // 对手方全部成交 → 从队列移除
        if (topBuy.getRemainingQuantity() <= 0) {
            buyOrders.poll();
            orderIndex.remove(topBuy.getOrderId());
        }

        return new MatchAtom(topBuy, newSell, tradePrice, tradeQty);
    }

    private void refreshStatus(OrderEntry order) {
        if (order.getRemainingQuantity() <= 0) {
            order.setStatus(OrderStatus.TRADED.name());
        } else if (order.getFilledQuantity() > 0) {
            order.setStatus(OrderStatus.PART_TRADED.name());
        }
    }

    // ================================================================
    // 订单簿基础操作
    // ================================================================

    public synchronized void addOrder(OrderEntry order) {
        orderIndex.put(order.getOrderId(), order);
        if (Side.BUY.name().equals(order.getSide())) {
            buyOrders.add(order);
        } else {
            sellOrders.add(order);
        }
    }

    /**
     * O(1) 按 orderId 撤单（借助 orderIndex）。
     */
    public synchronized OrderEntry removeOrder(String orderId) {
        OrderEntry entry = orderIndex.remove(orderId);
        if (entry == null) return null;
        if (Side.BUY.name().equals(entry.getSide())) {
            buyOrders.remove(entry);
        } else {
            sellOrders.remove(entry);
        }
        return entry;
    }

    public synchronized OrderEntry getTopBuy() {
        return buyOrders.peek();
    }

    public synchronized OrderEntry getTopSell() {
        return sellOrders.peek();
    }

    public synchronized OrderEntry popTopBuy() {
        OrderEntry e = buyOrders.poll();
        if (e != null) orderIndex.remove(e.getOrderId());
        return e;
    }

    public synchronized OrderEntry popTopSell() {
        OrderEntry e = sellOrders.poll();
        if (e != null) orderIndex.remove(e.getOrderId());
        return e;
    }

    public synchronized List<OrderEntry> getAllBuyOrders() {
        List<OrderEntry> list = new ArrayList<>(buyOrders);
        list.sort(buyOrders.comparator());
        return list;
    }

    public synchronized List<OrderEntry> getAllSellOrders() {
        List<OrderEntry> list = new ArrayList<>(sellOrders);
        list.sort(sellOrders.comparator());
        return list;
    }

    public synchronized BigDecimal getBidPrice() {
        OrderEntry top = getTopBuy();
        return top != null ? top.getPrice() : null;
    }

    public synchronized BigDecimal getAskPrice() {
        OrderEntry top = getTopSell();
        return top != null ? top.getPrice() : null;
    }

    public synchronized List<OrderEntry> clearAll() {
        List<OrderEntry> all = new ArrayList<>(buyOrders);
        all.addAll(sellOrders);
        buyOrders.clear();
        sellOrders.clear();
        orderIndex.clear();
        return all;
    }

    public synchronized Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("stockCode", stockCode);
        stats.put("buyCount", buyOrders.size());
        stats.put("sellCount", sellOrders.size());
        stats.put("bidPrice", getBidPrice());
        stats.put("askPrice", getAskPrice());
        stats.put("totalBuyQuantity", buyOrders.stream().mapToInt(OrderEntry::getRemainingQuantity).sum());
        stats.put("totalSellQuantity", sellOrders.stream().mapToInt(OrderEntry::getRemainingQuantity).sum());
        return stats;
    }
}
