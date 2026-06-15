package com.trading.central.controller;

import com.trading.central.model.CancelCommandMsg;
import com.trading.central.model.OrderCommandMsg;
import com.trading.central.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/central-trading/orders")
public class OrderController {

    private final OrderService orderService;
    private final JdbcTemplate jdbcTemplate;

    public OrderController(OrderService orderService, JdbcTemplate jdbcTemplate) {
        this.orderService = orderService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submitOrder(@RequestBody Map<String, Object> body) {
        String orderId = body.containsKey("orderNo") ? body.get("orderNo").toString() :
                         body.containsKey("orderId") ? body.get("orderId").toString() :
                         "O" + System.currentTimeMillis();

        String accountId = body.containsKey("fundAccountNo") ? body.get("fundAccountNo").toString() :
                           body.containsKey("accountId") ? body.get("accountId").toString() : null;

        String side = body.containsKey("direction") ? body.get("direction").toString() :
                      body.containsKey("side") ? body.get("side").toString() : null;

        OrderCommandMsg msg = new OrderCommandMsg();
        msg.setAccountId(accountId);
        msg.setOrderId(orderId);
        msg.setStockCode(body.get("stockCode") != null ? body.get("stockCode").toString() : null);
        msg.setSide(side);
        msg.setPrice(body.get("price") != null ? new BigDecimal(body.get("price").toString()) : null);
        msg.setQuantity(body.get("quantity") != null ? Integer.parseInt(body.get("quantity").toString()) : null);
        msg.setTimestamp(body.containsKey("timestamp") ? body.get("timestamp").toString() : LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        orderService.receiveOrder(msg);

        Map<String, Object> data = new HashMap<>();
        data.put("accepted", true);
        data.put("orderNo", orderId);
        data.put("status", "SUBMITTED");

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);

        return ResponseEntity.status(202).body(response);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable String orderId, @RequestBody Map<String, Object> body) {
        String accountId = body.containsKey("fundAccountNo") ? body.get("fundAccountNo").toString() :
                           body.containsKey("accountId") ? body.get("accountId").toString() : null;

        CancelCommandMsg msg = new CancelCommandMsg();
        msg.setOrderId(orderId);
        msg.setAccountId(accountId);
        msg.setTimestamp(body.containsKey("timestamp") ? body.get("timestamp").toString() : LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        orderService.cancelOrder(msg);

        Map<String, Object> data = new HashMap<>();
        data.put("canceled", true);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);

        return ResponseEntity.status(202).body(response);
    }

    @GetMapping("/{orderId}/result")
    public ResponseEntity<Map<String, Object>> getOrderResult(@PathVariable String orderId) {
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT order_id, account_id, stock_code, side, price, quantity, filled_quantity, remaining_quantity, status, entry_time, update_time " +
                "FROM order_book WHERE order_id = ?", orderId);

        if (orders.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "委托不存在");
            return ResponseEntity.status(404).body(response);
        }

        Map<String, Object> order = orders.get(0);

        List<Map<String, Object>> trades = jdbcTemplate.queryForList(
                "SELECT trade_no, trade_price, trade_quantity, trade_amount, trade_time " +
                "FROM trade_record WHERE buyer_order_id = ? OR seller_order_id = ? ORDER BY trade_time", orderId, orderId);

        BigDecimal weightedPrice = BigDecimal.ZERO;
        int totalTraded = 0;
        for (Map<String, Object> t : trades) {
            BigDecimal price = new BigDecimal(t.get("trade_price").toString());
            int qty = Integer.parseInt(t.get("trade_quantity").toString());
            weightedPrice = weightedPrice.add(price.multiply(new BigDecimal(qty)));
            totalTraded += qty;
        }

        if (totalTraded > 0) {
            weightedPrice = weightedPrice.divide(new BigDecimal(totalTraded), 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.get("order_id"));
        data.put("status", order.get("status"));
        data.put("stockCode", order.get("stock_code"));
        data.put("side", order.get("side"));
        data.put("orderPrice", new BigDecimal(order.get("price").toString()));
        data.put("orderQuantity", Integer.parseInt(order.get("quantity").toString()));
        data.put("filledQuantity", Integer.parseInt(order.get("filled_quantity").toString()));
        data.put("remainingQuantity", Integer.parseInt(order.get("remaining_quantity").toString()));
        data.put("tradePrice", totalTraded > 0 ? weightedPrice : null);
        data.put("tradedQuantity", totalTraded);
        data.put("tradeTime", trades.isEmpty() ? null : trades.get(trades.size() - 1).get("trade_time"));
        data.put("trades", trades.stream().map(t -> {
            Map<String, Object> tm = new HashMap<>();
            tm.put("tradeNo", t.get("trade_no"));
            tm.put("tradePrice", new BigDecimal(t.get("trade_price").toString()));
            tm.put("tradeQuantity", Integer.parseInt(t.get("trade_quantity").toString()));
            tm.put("tradeAmount", new BigDecimal(t.get("trade_amount").toString()));
            tm.put("tradeTime", t.get("trade_time"));
            return tm;
        }).collect(Collectors.toList()));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);

        return ResponseEntity.ok(response);
    }
}
