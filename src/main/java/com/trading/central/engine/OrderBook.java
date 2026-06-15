package com.trading.central.engine;

import com.trading.central.model.OrderEntry;
import com.trading.central.util.Constants.Side;

import java.math.BigDecimal;
import java.util.*;

public class OrderBook {
    private final String stockCode;
    
    // 买方：价格降序，同价时间升序
    private final PriorityQueue<OrderEntry> buyOrders;
    
    // 卖方：价格升序，同价时间升序
    private final PriorityQueue<OrderEntry> sellOrders;

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

    public synchronized void addOrder(OrderEntry order) {
        if (Side.BUY.name().equals(order.getSide())) {
            buyOrders.add(order);
        } else {
            sellOrders.add(order);
        }
    }

    public synchronized OrderEntry removeOrder(String orderId) {
        for (OrderEntry o : buyOrders) {
            if (o.getOrderId().equals(orderId)) {
                buyOrders.remove(o);
                return o;
            }
        }
        for (OrderEntry o : sellOrders) {
            if (o.getOrderId().equals(orderId)) {
                sellOrders.remove(o);
                return o;
            }
        }
        return null;
    }

    public synchronized OrderEntry getTopBuy() {
        return buyOrders.peek();
    }

    public synchronized OrderEntry getTopSell() {
        return sellOrders.peek();
    }

    public synchronized OrderEntry popTopBuy() {
        return buyOrders.poll();
    }

    public synchronized OrderEntry popTopSell() {
        return sellOrders.poll();
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
