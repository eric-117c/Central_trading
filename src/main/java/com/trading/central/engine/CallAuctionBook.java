package com.trading.central.engine;

import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 集合竞价订单簿（per stock）。
 *
 * 在集合竞价阶段，订单不立即撮合，而是收集到此订单簿中。
 * 到达集合竞价时间后，系统以最大成交量原则确定单一成交价，批量撮合。
 *
 * 与连续竞价 OrderBook 的区别：
 * - 不维护 PriorityQueue（不需要逐笔撮合）
 * - 使用 LinkedHashMap 保留订单插入顺序（时间优先的基础）
 * - 提供 determineAuctionPrice() 和 matchAtPrice() 两个核心算法
 */
@Slf4j
public class CallAuctionBook {

    private final String stockCode;

    // 保留插入顺序（即时间顺序），用于撮合阶段的时间优先排序
    private final Map<String, OrderEntry> buyOrders = new LinkedHashMap<>();
    private final Map<String, OrderEntry> sellOrders = new LinkedHashMap<>();

    public CallAuctionBook(String stockCode) {
        this.stockCode = stockCode;
    }

    // ================================================================
    // 订单收集
    // ================================================================

    public synchronized void addOrder(OrderEntry order) {
        if (Side.BUY.name().equals(order.getSide())) {
            buyOrders.put(order.getOrderId(), order);
        } else {
            sellOrders.put(order.getOrderId(), order);
        }
    }

    public synchronized OrderEntry removeOrder(String orderId) {
        OrderEntry entry = buyOrders.remove(orderId);
        if (entry != null) return entry;
        return sellOrders.remove(orderId);
    }

    public synchronized boolean isEmpty() {
        return buyOrders.isEmpty() || sellOrders.isEmpty();
        // 任一边为空都无法撮合
    }

    // ================================================================
    // 核心算法：最大成交量价格发现
    // ================================================================

    /**
     * 以最大成交量原则确定集合竞价成交价。
     *
     * 算法：
     * 1. 按价格分组统计买卖双方在各价位的总量
     * 2. 对每个候选价格 P，计算 cumBuy(P)=Σ{买量|价格>=P}，cumSell(P)=Σ{卖量|价格<=P}
     * 3. matchVol(P) = min(cumBuy(P), cumSell(P))
     * 4. 选择使 matchVol 最大的 P
     * 5. 平局时选择最接近前收盘价的 P
     *
     * @param previousClose 前收盘价
     * @param limiter       涨跌停限制器（用于成交价钳制）
     * @return 确定的成交价，若无法确定则返回 null
     */
    public synchronized BigDecimal determineAuctionPrice(BigDecimal previousClose, PriceLimiter limiter) {
        if (buyOrders.isEmpty() || sellOrders.isEmpty()) {
            return null;
        }

        // Step 1: 按价格分组统计
        TreeMap<BigDecimal, Integer> buyPriceLevels = new TreeMap<>(Comparator.reverseOrder());
        for (OrderEntry o : buyOrders.values()) {
            buyPriceLevels.merge(o.getPrice(), o.getRemainingQuantity(), Integer::sum);
        }

        TreeMap<BigDecimal, Integer> sellPriceLevels = new TreeMap<>();
        for (OrderEntry o : sellOrders.values()) {
            sellPriceLevels.merge(o.getPrice(), o.getRemainingQuantity(), Integer::sum);
        }

        // Step 2: 收集所有候选价格（买卖价格 + 前收盘价）
        TreeSet<BigDecimal> candidates = new TreeSet<>();
        candidates.addAll(buyPriceLevels.keySet());
        candidates.addAll(sellPriceLevels.keySet());
        candidates.add(previousClose);

        // 总买卖量（用于快速计算累积量）
        int totalBuyQty = buyPriceLevels.values().stream().mapToInt(Integer::intValue).sum();
        int totalSellQty = sellPriceLevels.values().stream().mapToInt(Integer::intValue).sum();

        // Step 3: 对每个候选价格计算 matchVol
        int maxMatchVol = -1;
        List<BigDecimal> bestPrices = new ArrayList<>();

        for (BigDecimal P : candidates) {
            // cumBuy(P): 所有价格 >= P 的买单总量
            int cumBuy = 0;
            for (Map.Entry<BigDecimal, Integer> e : buyPriceLevels.entrySet()) {
                if (e.getKey().compareTo(P) >= 0) {
                    cumBuy += e.getValue();
                } else {
                    break; // buyPriceLevels 降序排列，后面都 < P
                }
            }

            // cumSell(P): 所有价格 <= P 的卖单总量
            int cumSell = 0;
            for (Map.Entry<BigDecimal, Integer> e : sellPriceLevels.entrySet()) {
                if (e.getKey().compareTo(P) <= 0) {
                    cumSell += e.getValue();
                } else {
                    break; // sellPriceLevels 升序排列，后面都 > P
                }
            }

            int matchVol = Math.min(cumBuy, cumSell);

            if (matchVol > maxMatchVol) {
                maxMatchVol = matchVol;
                bestPrices.clear();
                bestPrices.add(P);
            } else if (matchVol == maxMatchVol) {
                bestPrices.add(P);
            }
        }

        if (maxMatchVol <= 0 || bestPrices.isEmpty()) {
            return null;
        }

        // Step 4: 平局规则 —— 选最接近前收盘价的
        BigDecimal bestPrice = bestPrices.get(0);
        BigDecimal minDist = bestPrice.subtract(previousClose).abs();

        for (int i = 1; i < bestPrices.size(); i++) {
            BigDecimal dist = bestPrices.get(i).subtract(previousClose).abs();
            if (dist.compareTo(minDist) < 0) {
                minDist = dist;
                bestPrice = bestPrices.get(i);
            }
        }

        // Step 5: 钳制到涨跌停范围
        bestPrice = limiter.clampTradePrice(stockCode, bestPrice);

        log.info("[集合竞价] {} 最大成交量={} 候选价位数={} 选定价格={}",
                stockCode, maxMatchVol, bestPrices.size(), bestPrice);
        return bestPrice;
    }

    // ================================================================
    // 执行撮合
    // ================================================================

    /**
     * 在确定成交价后，按时间优先撮合所有符合条件的订单。
     *
     * 符合条件的买单：price >= auctionPrice
     * 符合条件的卖单：price <= auctionPrice
     *
     * 买卖双方各自按 entryTime 升序排列，依次配对直到一方耗尽。
     * 每笔成交价均为 auctionPrice。
     *
     * @param auctionPrice 集合竞价确定的成交价
     * @return 成交列表
     */
    public synchronized List<OrderBook.MatchAtom> matchAtPrice(BigDecimal auctionPrice) {
        List<OrderBook.MatchAtom> trades = new ArrayList<>();

        // 收集符合条件的订单，按时间排序
        List<OrderEntry> eligibleBuys = new ArrayList<>();
        for (OrderEntry o : buyOrders.values()) {
            if (o.getPrice().compareTo(auctionPrice) >= 0) {
                eligibleBuys.add(o);
            }
        }
        eligibleBuys.sort(Comparator.comparing(OrderEntry::getEntryTime));

        List<OrderEntry> eligibleSells = new ArrayList<>();
        for (OrderEntry o : sellOrders.values()) {
            if (o.getPrice().compareTo(auctionPrice) <= 0) {
                eligibleSells.add(o);
            }
        }
        eligibleSells.sort(Comparator.comparing(OrderEntry::getEntryTime));

        // 按时间优先依次配对
        int bi = 0, si = 0;
        while (bi < eligibleBuys.size() && si < eligibleSells.size()) {
            OrderEntry buy = eligibleBuys.get(bi);
            OrderEntry sell = eligibleSells.get(si);

            int tradeQty = Math.min(buy.getRemainingQuantity(), sell.getRemainingQuantity());

            // 更新数量
            buy.setFilledQuantity(buy.getFilledQuantity() + tradeQty);
            buy.setRemainingQuantity(buy.getRemainingQuantity() - tradeQty);
            sell.setFilledQuantity(sell.getFilledQuantity() + tradeQty);
            sell.setRemainingQuantity(sell.getRemainingQuantity() - tradeQty);

            // 更新状态
            refreshStatus(buy);
            refreshStatus(sell);

            trades.add(new OrderBook.MatchAtom(buy, sell, auctionPrice, tradeQty));

            // 移动到下一个未耗尽的订单
            if (buy.getRemainingQuantity() <= 0) bi++;
            if (sell.getRemainingQuantity() <= 0) si++;
        }

        return trades;
    }

    /**
     * 返回所有剩余未成交订单（含未成交和部分成交的订单），并清空订单簿。
     * 这些订单将转入连续竞价订单簿。
     */
    public synchronized List<OrderEntry> drainRemainingOrders() {
        List<OrderEntry> remaining = new ArrayList<>();
        for (OrderEntry o : buyOrders.values()) {
            if (o.getRemainingQuantity() > 0) {
                remaining.add(o);
            }
        }
        for (OrderEntry o : sellOrders.values()) {
            if (o.getRemainingQuantity() > 0) {
                remaining.add(o);
            }
        }
        // 已完全成交的订单不需要转移（它们已经 TRADED 状态）
        buyOrders.clear();
        sellOrders.clear();
        return remaining;
    }

    private void refreshStatus(OrderEntry order) {
        if (order.getRemainingQuantity() <= 0) {
            order.setStatus(OrderStatus.TRADED.name());
        } else if (order.getFilledQuantity() > 0) {
            order.setStatus(OrderStatus.PART_TRADED.name());
        }
    }

    // ================================================================
    // 查询
    // ================================================================

    public synchronized List<OrderEntry> getAllBuyOrders() {
        return new ArrayList<>(buyOrders.values());
    }

    public synchronized List<OrderEntry> getAllSellOrders() {
        return new ArrayList<>(sellOrders.values());
    }

    public synchronized Map<String, Object> getSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("stockCode", stockCode);
        snapshot.put("buyOrders", getAllBuyOrders());
        snapshot.put("sellOrders", getAllSellOrders());
        snapshot.put("buyCount", buyOrders.size());
        snapshot.put("sellCount", sellOrders.size());
        return snapshot;
    }
}
