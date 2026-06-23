package com.vitalys.trading_grid.service;

import com.vitalys.trading_grid.dto.BacktestRequest;
import com.vitalys.trading_grid.dto.BacktestResult;
import com.vitalys.trading_grid.dto.BacktestTradeLogEntry;
import com.vitalys.trading_grid.gateway.backtest.BacktestMarketGateway;
import com.vitalys.trading_grid.gateway.dto.Candle;
import com.vitalys.trading_grid.model.OrderType;
import com.vitalys.trading_grid.model.TradingPair;
import com.vitalys.trading_grid.repository.TradingPairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {
    private final HistoricalDataService historicalDataService;
    private final BacktestMarketGateway backtestMarketGateway;
    private final StrategyEngine strategyEngine;
    private final TradingPairRepository tradingPairRepository;

    public BacktestResult run(BacktestRequest request) {
        List<Candle> candles = historicalDataService.loadCandles(request.symbol(), request.interval())
                .stream()
                .filter(candle -> !candle.getOpenTime().isBefore(request.from())
                        && !candle.getOpenTime().isAfter(request.to()))
                .sorted(Comparator.comparing(Candle::getOpenTime))
                .toList();

        if (candles.isEmpty()) {
            throw new IllegalStateException(
                    "No historical candles found for symbol=%s interval=%s in range [%s, %s] — download data first via POST /historical-data/{symbol}/download"
                            .formatted(request.symbol(), request.interval(), request.from(), request.to()));
        }

        log.info("Starting backtest for {} @ interval={} — {} candles from {} to {}",
                request.symbol(), request.interval(), candles.size(), request.from(), request.to());

        backtestMarketGateway.loadSeries(candles);

        var state = new BacktestState(request.initialWallet(), request.spendPerOrder(),
                request.orderCount(), request.profitPercent());

        TradingPair pair = TradingPair.builder()
                .active(true)
                .orderCount(20)
                .symbol("ETHUSDT")
                .gateway("backtest")
                .profitPercent(BigDecimal.ONE)
                .spendPerOrder(BigDecimal.valueOf(100))
                .wallet(BigDecimal.valueOf(10000))
                .build();
        tradingPairRepository.save(pair);
        while (backtestMarketGateway.advance()) {
            Candle candle = backtestMarketGateway.currentCandle();
            log.info("Candle {}", candle);

            strategyEngine.execute(pair);
        }

        BigDecimal totalProfit = state.wallet.subtract(request.initialWallet());

        log.info("Backtest complete for {} @ interval={} — finalWallet={} completedCycles={} totalProfit={}",
                request.symbol(), request.interval(), state.wallet, state.completedCycles, totalProfit);

        return new BacktestResult(
                request.symbol(),
                request.interval(),
                candles.size(),
                request.initialWallet(),
                state.wallet,
                state.completedCycles,
                totalProfit,
                List.copyOf(state.tradeLog)
        );
    }

    private static final class BacktestState {
        private BigDecimal wallet;
        private final BigDecimal spendPerOrder;
        private final int orderCount;
        private final BigDecimal profitPercent;
        private final List<SimulatedOrder> openOrders = new ArrayList<>();
        private final List<BacktestTradeLogEntry> tradeLog = new ArrayList<>();
        private int completedCycles = 0;

        private BacktestState(BigDecimal wallet, BigDecimal spendPerOrder, int orderCount, BigDecimal profitPercent) {
            this.wallet = wallet;
            this.spendPerOrder = spendPerOrder;
            this.orderCount = orderCount;
            this.profitPercent = profitPercent;
        }
    }

    private static final class SimulatedOrder {
        private final OrderType type;
        private final BigDecimal price;
        private final BigDecimal qty;
        private SimulatedOrderStatus status;

        private SimulatedOrder(OrderType type, BigDecimal price, BigDecimal qty) {
            this.type = type;
            this.price = price;
            this.qty = qty;
            this.status = SimulatedOrderStatus.OPEN;
        }

        private OrderType type() {
            return type;
        }

        private BigDecimal price() {
            return price;
        }

        private BigDecimal qty() {
            return qty;
        }

        private SimulatedOrderStatus status() {
            return status;
        }

        private void markFilled() {
            this.status = SimulatedOrderStatus.FILLED;
        }
    }

    private enum SimulatedOrderStatus {
        OPEN, FILLED
    }
}
