package com.trading.central.engine;

import com.trading.central.dashboard.TradingEventBroadcaster;
import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中央交易撮合引擎。
 *
 * 支持两种撮合模式：
 * 1. 连续竞价（Continuous Auction）—— 逐笔撮合，中间价算法
 * 2. 集合竞价（Call Auction）—— 收集订单后批量撮合，最大成交量价格
 */
@Slf4j
@Component
public class MatchingEngine {

    private final PriceLimiter priceLimiter;
    private final TradingEventBroadcaster broadcaster;

    // 连续竞价订单簿（per stock）
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

    // 集合竞价订单簿（per stock），仅在 CALL_AUCTION 阶段使用
    private final Map<String, CallAuctionBook> callAuctionBooks = new ConcurrentHashMap<>();

    // 当前交易阶段
    private volatile AuctionPhase currentPhase = AuctionPhase.CONTINUOUS_AUCTION;

    public MatchingEngine(PriceLimiter priceLimiter, TradingEventBroadcaster broadcaster) {
        this.priceLimiter = priceLimiter;
        this.broadcaster = broadcaster;
    }

    // ================================================================
    // 交易阶段管理
    // ================================================================

    public enum AuctionPhase {
        CALL_AUCTION,
        CONTINUOUS_AUCTION
    }

    public AuctionPhase getCurrentPhase() {
        return currentPhase;
    }

    public void enterCallAuction() {
        this.currentPhase = AuctionPhase.CALL_AUCTION;
        callAuctionBooks.clear();
        log.info("[撮合引擎] 进入集合竞价阶段");
        broadcaster.system("进入集合竞价阶段");
    }

    public void enterContinuousAuction() {
        this.currentPhase = AuctionPhase.CONTINUOUS_AUCTION;
        log.info("[撮合引擎] 进入连续竞价阶段");
        broadcaster.system("进入连续竞价阶段");
    }

    // ================================================================
    // 连续竞价
    // ================================================================

    public OrderBook getOrderBook(String stockCode) {
        return orderBooks.computeIfAbsent(stockCode, OrderBook::new);
    }

    /**
     * 连续竞价撮合回调。
     */
    public interface TradeCallback {
        void onTrade(OrderEntry buyOrder, OrderEntry sellOrder, BigDecimal tradePrice, int tradeQty) throws Exception;
    }

    /**
     * 连续竞价撮合结果。
     */
    public static class MatchResult {
        public List<TradeInfo> trades = new ArrayList<>();
        public String finalStatus;
    }

    /**
     * 单笔成交信息。
     */
    public static class TradeInfo {
        public OrderEntry buyOrder;
        public OrderEntry sellOrder;
        public BigDecimal tradePrice;
        public int tradeQty;

        public TradeInfo(OrderEntry buyOrder, OrderEntry sellOrder, BigDecimal tradePrice, int tradeQty) {
            this.buyOrder = buyOrder;
            this.sellOrder = sellOrder;
            this.tradePrice = tradePrice;
            this.tradeQty = tradeQty;
        }
    }

    /**
     * 连续竞价：将新订单与订单簿中对手方撮合。
     * 每次匹配调用 tryMatchBuy/tryMatchSell（原子操作，无 TOCTOU 窗口）。
     */
    public MatchResult matchOrder(OrderEntry newOrder, TradeCallback onTrade) throws Exception {
        OrderBook book = getOrderBook(newOrder.getStockCode());
        MatchResult result = new MatchResult();

        boolean isBuy = Side.BUY.name().equals(newOrder.getSide());

        while (newOrder.getRemainingQuantity() > 0) {
            OrderBook.MatchAtom atom;
            if (isBuy) {
                atom = book.tryMatchBuy(newOrder, priceLimiter);
            } else {
                atom = book.tryMatchSell(newOrder, priceLimiter);
            }

            if (atom == null) {
                log.debug("[撮合] {} 无对手方或价格不匹配，挂单等待", newOrder.getStockCode());
                break;
            }

            log.info("[撮合成交] {} 价格={} 数量={} 买方={} 卖方={}",
                    newOrder.getStockCode(), atom.tradePrice, atom.tradeQty,
                    atom.buyOrder.getOrderId(), atom.sellOrder.getOrderId());

            if (onTrade != null) {
                onTrade.onTrade(atom.buyOrder, atom.sellOrder, atom.tradePrice, atom.tradeQty);
            }

            result.trades.add(new TradeInfo(atom.buyOrder, atom.sellOrder, atom.tradePrice, atom.tradeQty));
        }

        // 确定最终状态
        if (newOrder.getRemainingQuantity() <= 0) {
            newOrder.setStatus(OrderStatus.TRADED.name());
        } else if (newOrder.getFilledQuantity() > 0) {
            newOrder.setStatus(OrderStatus.PART_TRADED.name());
            book.addOrder(newOrder);
        } else {
            newOrder.setStatus(OrderStatus.ACCEPTED.name());
            book.addOrder(newOrder);
        }

        result.finalStatus = newOrder.getStatus();
        return result;
    }

    // ================================================================
    // 集合竞价
    // ================================================================

    public CallAuctionBook getCallAuctionBook(String stockCode) {
        return callAuctionBooks.computeIfAbsent(stockCode, CallAuctionBook::new);
    }

    /**
     * 将订单加入集合竞价订单簿（不立即撮合）。
     */
    public void addToCallAuction(OrderEntry order) {
        CallAuctionBook book = getCallAuctionBook(order.getStockCode());
        book.addOrder(order);
        log.debug("[集合竞价] {} 订单已收集: {} side={} price={} qty={}",
                order.getStockCode(), order.getOrderId(), order.getSide(),
                order.getPrice(), order.getQuantity());
    }

    /**
     * 对指定股票执行集合竞价。
     *
     * @param stockCode 股票代码
     * @param previousClose 前收盘价
     * @param onTrade 成交回调（与连续竞价共用 TradeService.executeTrade）
     * @return 集合竞价结果；若该股票无集合竞价订单则返回 null
     */
    public AuctionResult runCallAuction(String stockCode, BigDecimal previousClose,
                                         TradeCallback onTrade) throws Exception {
        CallAuctionBook book = callAuctionBooks.get(stockCode);
        if (book == null) {
            log.debug("[集合竞价] {} 无订单簿，跳过", stockCode);
            return null;
        }

        // 单边订单无法撮合，但需转入连续竞价（否则订单丢失）
        if (book.isEmpty()) {
            log.info("[集合竞价] {} 仅有单边订单，直接转入连续竞价", stockCode);
            return transferToContinuous(stockCode, book);
        }

        // 1. 确定集合竞价成交价（最大成交量原则）
        BigDecimal auctionPrice = book.determineAuctionPrice(previousClose, priceLimiter);
        if (auctionPrice == null) {
            log.info("[集合竞价] {} 无法确定成交价（无可匹配价格），订单转入连续竞价", stockCode);
            return transferToContinuous(stockCode, book);
        }

        log.info("[集合竞价] {} 确定成交价={}", stockCode, auctionPrice);

        // 2. 按时间优先撮合所有符合条件订单
        List<OrderBook.MatchAtom> matches = book.matchAtPrice(auctionPrice);

        AuctionResult result = new AuctionResult();
        result.auctionPrice = auctionPrice;

        for (OrderBook.MatchAtom atom : matches) {
            log.info("[集合竞价成交] {} 价格={} 数量={} 买方={} 卖方={}",
                    stockCode, auctionPrice, atom.tradeQty,
                    atom.buyOrder.getOrderId(), atom.sellOrder.getOrderId());

            if (onTrade != null) {
                onTrade.onTrade(atom.buyOrder, atom.sellOrder, auctionPrice, atom.tradeQty);
            }
            result.trades.add(new TradeInfo(atom.buyOrder, atom.sellOrder, auctionPrice, atom.tradeQty));
            result.totalTradeQty += atom.tradeQty;
        }

        // 3. 未成交剩余订单转入连续竞价订单簿
        List<OrderEntry> remaining = book.drainRemainingOrders();
        OrderBook continuousBook = getOrderBook(stockCode);
        for (OrderEntry order : remaining) {
            continuousBook.addOrder(order);
        }
        result.remainingCount = remaining.stream().filter(o -> o.getRemainingQuantity() > 0).count();

        // 4. 清理集合竞价订单簿
        callAuctionBooks.remove(stockCode);

        log.info("[集合竞价] {} 完成: 成交{}笔 总成交量={} 剩余{}单转入连续竞价",
                stockCode, result.trades.size(), result.totalTradeQty, result.remainingCount);

        return result;
    }

    /**
     * 无法确定成交价时，将所有订单转入连续竞价。
     */
    private AuctionResult transferToContinuous(String stockCode, CallAuctionBook book) {
        List<OrderEntry> remaining = book.drainRemainingOrders();
        OrderBook continuousBook = getOrderBook(stockCode);
        for (OrderEntry order : remaining) {
            continuousBook.addOrder(order);
        }
        callAuctionBooks.remove(stockCode);

        AuctionResult result = new AuctionResult();
        result.auctionPrice = null;
        result.remainingCount = remaining.size();
        return result;
    }

    /**
     * 集合竞价结果。
     */
    public static class AuctionResult {
        public BigDecimal auctionPrice;
        public List<TradeInfo> trades = new ArrayList<>();
        public int totalTradeQty;
        public long remainingCount;
    }

    // ================================================================
    // 通用操作
    // ================================================================

    public OrderEntry cancelOrderInBook(String orderId, String stockCode) {
        // 先查连续竞价订单簿
        if (stockCode != null) {
            OrderBook book = orderBooks.get(stockCode);
            if (book != null) {
                OrderEntry removed = book.removeOrder(orderId);
                if (removed != null) return removed;
            }
            // 再查集合竞价订单簿
            CallAuctionBook caBook = callAuctionBooks.get(stockCode);
            if (caBook != null) {
                OrderEntry removed = caBook.removeOrder(orderId);
                if (removed != null) return removed;
            }
            return null;
        }
        // 遍历所有连续竞价订单簿
        for (OrderBook book : orderBooks.values()) {
            OrderEntry removed = book.removeOrder(orderId);
            if (removed != null) return removed;
        }
        // 遍历所有集合竞价订单簿
        for (CallAuctionBook book : callAuctionBooks.values()) {
            OrderEntry removed = book.removeOrder(orderId);
            if (removed != null) return removed;
        }
        return null;
    }

    public Map<String, Object> getBookSnapshot(String stockCode) {
        OrderBook book = orderBooks.get(stockCode);
        Map<String, Object> snapshot = new HashMap<>();
        if (book == null) {
            snapshot.put("stockCode", stockCode);
            snapshot.put("buyOrders", Collections.emptyList());
            snapshot.put("sellOrders", Collections.emptyList());
            snapshot.put("stats", null);
            return snapshot;
        }
        snapshot.put("stockCode", stockCode);
        snapshot.put("buyOrders", book.getAllBuyOrders());
        snapshot.put("sellOrders", book.getAllSellOrders());
        snapshot.put("stats", book.getStats());
        return snapshot;
    }

    public Map<String, BigDecimal> getTopPrices(String stockCode) {
        OrderBook book = orderBooks.get(stockCode);
        Map<String, BigDecimal> prices = new HashMap<>();
        if (book == null) {
            prices.put("bidPrice", null);
            prices.put("askPrice", null);
        } else {
            prices.put("bidPrice", book.getBidPrice());
            prices.put("askPrice", book.getAskPrice());
        }
        return prices;
    }

    public List<OrderEntry> clearBookOrders(String stockCode) {
        OrderBook book = orderBooks.get(stockCode);
        if (book == null) return Collections.emptyList();
        return book.clearAll();
    }

    public List<String> getAllStockCodes() {
        return new ArrayList<>(orderBooks.keySet());
    }

    /**
     * 获取某只股票的集合竞价订单簿快照（调试用）。
     */
    public Map<String, Object> getCallAuctionSnapshot(String stockCode) {
        CallAuctionBook book = callAuctionBooks.get(stockCode);
        if (book == null) {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("stockCode", stockCode);
            snapshot.put("buyOrders", Collections.emptyList());
            snapshot.put("sellOrders", Collections.emptyList());
            return snapshot;
        }
        return book.getSnapshot();
    }

    /**
     * 获取所有有集合竞价订单的股票代码列表。
     */
    public List<String> getCallAuctionStockCodes() {
        return new ArrayList<>(callAuctionBooks.keySet());
    }
}
