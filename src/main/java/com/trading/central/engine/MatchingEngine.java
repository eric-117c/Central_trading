package com.trading.central.engine;

import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.OrderStatus;
import com.trading.central.util.Constants.Side;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MatchingEngine {

    private final PriceLimiter priceLimiter;
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

    public MatchingEngine(PriceLimiter priceLimiter) {
        this.priceLimiter = priceLimiter;
    }

    public OrderBook getOrderBook(String stockCode) {
        return orderBooks.computeIfAbsent(stockCode, OrderBook::new);
    }

    public interface TradeCallback {
        void onTrade(OrderEntry buyOrder, OrderEntry sellOrder, BigDecimal tradePrice, int tradeQty) throws Exception;
    }

    public static class MatchResult {
        public List<TradeInfo> trades = new ArrayList<>();
        public String finalStatus;
    }

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

    public MatchResult matchOrder(OrderEntry newOrder, TradeCallback onTrade) throws Exception {
        OrderBook book = getOrderBook(newOrder.getStockCode());
        MatchResult result = new MatchResult();

        while (newOrder.getRemainingQuantity() > 0) {
            OrderEntry counter = Side.BUY.name().equals(newOrder.getSide()) ? book.getTopSell() : book.getTopBuy();

            if (counter == null) {
                log.debug("[撮合] {} 无对手方委托，挂单等待", newOrder.getStockCode());
                break;
            }

            if (Side.BUY.name().equals(newOrder.getSide()) && newOrder.getPrice().compareTo(counter.getPrice()) < 0) {
                log.debug("[撮合] 买价 {} < 最低卖价 {}，不匹配", newOrder.getPrice(), counter.getPrice());
                break;
            }
            if (Side.SELL.name().equals(newOrder.getSide()) && newOrder.getPrice().compareTo(counter.getPrice()) > 0) {
                log.debug("[撮合] 卖价 {} > 最高买价 {}，不匹配", newOrder.getPrice(), counter.getPrice());
                break;
            }

            BigDecimal rawPrice = newOrder.getPrice().add(counter.getPrice()).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            BigDecimal tradePrice = priceLimiter.clampTradePrice(newOrder.getStockCode(), rawPrice);

            int tradeQty = Math.min(newOrder.getRemainingQuantity(), counter.getRemainingQuantity());

            newOrder.setFilledQuantity(newOrder.getFilledQuantity() + tradeQty);
            newOrder.setRemainingQuantity(newOrder.getRemainingQuantity() - tradeQty);
            counter.setFilledQuantity(counter.getFilledQuantity() + tradeQty);
            counter.setRemainingQuantity(counter.getRemainingQuantity() - tradeQty);

            OrderEntry buyOrder = Side.BUY.name().equals(newOrder.getSide()) ? newOrder : counter;
            OrderEntry sellOrder = Side.SELL.name().equals(newOrder.getSide()) ? newOrder : counter;

            log.info("[撮合成交] {} 价格={} 数量={} 买方={} 卖方={}", newOrder.getStockCode(), tradePrice, tradeQty, buyOrder.getOrderId(), sellOrder.getOrderId());

            if (onTrade != null) {
                onTrade.onTrade(buyOrder, sellOrder, tradePrice, tradeQty);
            }

            result.trades.add(new TradeInfo(buyOrder, sellOrder, tradePrice, tradeQty));

            if (counter.getRemainingQuantity() <= 0) {
                counter.setStatus(OrderStatus.TRADED.name());
                if (Side.BUY.name().equals(newOrder.getSide())) {
                    book.popTopSell();
                } else {
                    book.popTopBuy();
                }
            } else {
                counter.setStatus(OrderStatus.PART_TRADED.name());
            }
        }

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

    public OrderEntry cancelOrderInBook(String orderId, String stockCode) {
        if (stockCode != null) {
            OrderBook book = orderBooks.get(stockCode);
            if (book != null) return book.removeOrder(orderId);
            return null;
        }
        for (OrderBook book : orderBooks.values()) {
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
}
