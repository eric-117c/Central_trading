package com.trading.central.scheduler;

import com.trading.central.engine.MatchingEngine;
import com.trading.central.engine.MatchingEngine.AuctionPhase;
import com.trading.central.engine.PriceLimiter;
import com.trading.central.service.TradeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 集合竞价定时调度器。
 *
 * 在交易日开盘前（默认9:15）自动进入集合竞价阶段，
 * 在集合竞价时间（默认9:25）自动执行集合竞价并切换到连续竞价阶段。
 *
 * 每日仅触发一次，通过 lastTriggerDate 防止重复执行。
 */
@Slf4j
@Component
public class CallAuctionScheduler {

    private final MatchingEngine matchingEngine;
    private final PriceLimiter priceLimiter;
    private final TradeService tradeService;

    @Value("${app.trading.call-auction-hour:9}")
    private int callAuctionHour;

    @Value("${app.trading.call-auction-minute:25}")
    private int callAuctionMinute;

    @Value("${app.trading.start-hour:9}")
    private int startHour;

    @Value("${app.trading.start-minute:30}")
    private int startMinute;

    private String lastTriggerDate = "";
    private boolean callAuctionEntered = false;

    public CallAuctionScheduler(MatchingEngine matchingEngine, PriceLimiter priceLimiter,
                                 TradeService tradeService) {
        this.matchingEngine = matchingEngine;
        this.priceLimiter = priceLimiter;
        this.tradeService = tradeService;
    }

    /**
     * 每分钟检查一次是否需要进入集合竞价 / 触发集合竞价。
     */
    @Scheduled(fixedRate = 60000)
    public void checkAndTrigger() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        String todayText = today.toString();

        // 新的一天，重置状态
        if (!todayText.equals(lastTriggerDate)) {
            callAuctionEntered = false;
        }

        // 已触发过，跳过
        if (todayText.equals(lastTriggerDate) && matchingEngine.getCurrentPhase() == AuctionPhase.CONTINUOUS_AUCTION) {
            return;
        }

        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        // Step 1: 到达开盘时间 → 进入集合竞价阶段（如果尚未进入）
        if (!callAuctionEntered && (currentHour > startHour
                || (currentHour == startHour && currentMinute >= startMinute))) {
            // 如果已经过了集合竞价时间，直接进入连续竞价
            if (currentHour > callAuctionHour
                    || (currentHour == callAuctionHour && currentMinute >= callAuctionMinute)) {
                log.info("[CallAuctionScheduler] 当前时间已过集合竞价时间，直接进入连续竞价阶段");
                matchingEngine.enterContinuousAuction();
                lastTriggerDate = todayText;
                return;
            }
            // 进入集合竞价阶段，收集订单
            matchingEngine.enterCallAuction();
            callAuctionEntered = true;
            log.info("[CallAuctionScheduler] 已自动进入集合竞价阶段（{}:{:02d}）", currentHour, currentMinute);
            return;
        }

        // Step 2: 到达集合竞价触发时间 → 执行集合竞价
        if (callAuctionEntered
                && matchingEngine.getCurrentPhase() == AuctionPhase.CALL_AUCTION
                && (currentHour > callAuctionHour
                    || (currentHour == callAuctionHour && currentMinute >= callAuctionMinute))) {

            log.info("[CallAuctionScheduler] 开始自动执行集合竞价...");

            int successCount = 0;
            int totalTradeQty = 0;

            for (String stockCode : matchingEngine.getCallAuctionStockCodes()) {
                try {
                    PriceLimiter.Limits limits = priceLimiter.getPriceLimits(stockCode);
                    if (limits == null) continue;

                    MatchingEngine.AuctionResult result = matchingEngine.runCallAuction(
                            stockCode, limits.getPreviousClose(), tradeService::executeTrade);

                    if (result != null) {
                        successCount++;
                        totalTradeQty += result.totalTradeQty;
                    }
                } catch (Exception e) {
                    log.error("[CallAuctionScheduler] {} 集合竞价失败", stockCode, e);
                }
            }

            // 切换到连续竞价
            matchingEngine.enterContinuousAuction();
            lastTriggerDate = todayText;

            log.info("[CallAuctionScheduler] 集合竞价自动执行完成：{} 只股票，总成交量 {}",
                    successCount, totalTradeQty);
        }
    }
}
