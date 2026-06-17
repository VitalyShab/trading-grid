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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Runs the grid trading strategy against previously downloaded {@link Candle} data, loaded from
 * per-symbol/interval JSON files via {@link HistoricalDataService#loadCandles}.
 *
 * <p>This service deliberately does NOT call {@link StrategyEngine#startStrategy()} — that
 * method drives live trading by reading/writing {@link com.vitalys.trading_grid.model.TradingPair}
 * and {@link com.vitalys.trading_grid.model.Order} JPA entities via
 * {@link com.vitalys.trading_grid.repository.TradingPairRepository} and
 * {@link com.vitalys.trading_grid.repository.OrderRepository}. Reusing it for backtests would
 * either persist thousands of simulated orders to the database (one per candle) or require
 * threading a "dry run" flag through the live trading code path — both of which risk live
 * trading correctness.
 *
 * <p>Instead, this service re-implements the same grid algorithm as
 * {@link StrategyEngine#placeGridOrders} and {@link StrategyEngine#syncFilledOrders} against a
 * purely in-memory {@link SimulatedOrder} list and wallet balance, using
 * {@link BacktestMarketGateway} to step through candles one at a time. The grid math
 * (step fraction, price scale, profit-percent multiplier) is kept identical to
 * {@link StrategyEngine} so backtest results are representative of live behaviour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    // Mirrors StrategyEngine — kept identical so backtest math matches live trading.
    private static final BigDecimal STEP_FRACTION = new BigDecimal("0.01");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int QTY_SCALE = 8;
    private static final int PRICE_SCALE = 8;

    private final HistoricalDataService historicalDataService;
    private final BacktestMarketGateway backtestMarketGateway;
    private final StrategyEngine strategyEngine;
    private final TradingPairRepository tradingPairRepository;

    /**
     * Runs a full backtest for the given request and returns the result.
     *
     * <p>Loads the candle series for {@code request.symbol()}/{@code request.interval()}
     * between {@code request.from()} and {@code request.to()} into
     * {@link BacktestMarketGateway}, then steps through every candle: each step first checks
     * open simulated orders for fills against the candle's OHLC range (via
     * {@link BacktestMarketGateway#wouldFill}), then places new grid orders at the candle's
     * close price exactly as {@link StrategyEngine#placeGridOrders} would.
     */
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

    /**
     * Mirrors {@link StrategyEngine#syncFilledOrders}: checks every OPEN simulated order
     * against the current candle and marks it FILLED if the candle's range satisfies it
     * (BUY fills if low &lt;= price, SELL fills if high &gt;= price — see
     * {@link BacktestMarketGateway#wouldFill}).
     *
     * <p>A filled SELL closes out all FILL'd BUY orders (a completed grid cycle): those BUY
     * orders are marked COMPLETE, the SELL proceeds are added to the wallet, and any remaining
     * OPEN buy orders are cancelled with their reserved spend refunded — exactly as
     * {@link com.vitalys.trading_grid.service.OrderService#proceedSellComplete} and
     * {@link com.vitalys.trading_grid.service.OrderService#cancelAllOpenBuyOrders} do for live
     * trading.
     */
    private void syncFilledOrders(BacktestState state, Candle candle) {
        for (SimulatedOrder order : List.copyOf(state.openOrders)) {
            boolean filled = backtestMarketGateway.wouldFill(order.type(), order.price());
            if (!filled) {
                continue;
            }

            log.debug("Simulated fill: type={} price={} candleLow={} candleHigh={} openTime={}",
                    order.type(), order.price(), candle.getLow(), candle.getHigh(), candle.getOpenTime());

            if (order.type() == OrderType.SELL) {
                completeSellCycle(state, order, candle);
            } else {
                order.markFilled();
                state.tradeLog.add(new BacktestTradeLogEntry(
                        candle.getOpenTime(), OrderType.BUY, order.price(), order.qty(), state.wallet));
            }
        }
    }

    /**
     * A SELL fill closes the cycle: completes all FILL'd BUY orders, adds the SELL proceeds to
     * the wallet, cancels any still-open BUY orders (refunding their reserved spend), and removes
     * all of those orders from the open-order list. Mirrors
     * {@link com.vitalys.trading_grid.service.OrderService#proceedSellComplete}.
     */
    private void completeSellCycle(BacktestState state, SimulatedOrder sellOrder, Candle candle) {
        Iterator<SimulatedOrder> iterator = state.openOrders.iterator();
        while (iterator.hasNext()) {
            SimulatedOrder order = iterator.next();
            if (order == sellOrder) {
                continue;
            }
            if (order.type() == OrderType.BUY && order.status() == SimulatedOrderStatus.OPEN) {
                // Cancel stale open buy orders and refund their reserved spend.
                state.wallet = state.wallet.add(order.qty().multiply(order.price()));
                iterator.remove();
            } else if (order.type() == OrderType.BUY && order.status() == SimulatedOrderStatus.FILLED) {
                iterator.remove();
            }
        }

        state.wallet = state.wallet.add(sellOrder.qty().multiply(sellOrder.price()));
        state.openOrders.remove(sellOrder);
        state.completedCycles++;

        state.tradeLog.add(new BacktestTradeLogEntry(
                candle.getOpenTime(), OrderType.SELL, sellOrder.price(), sellOrder.qty(), state.wallet));
    }

    /**
     * Mirrors {@link StrategyEngine#placeGridOrders}: places new BUY orders below the current
     * price and a single SELL order above the average price of FILL'd BUY orders, using the
     * candle's close price as the "current price" — analogous to
     * {@link StrategyEngine#fetchCurrentPrice} returning {@code getCandles(...).getLast().getClose()}.
     */
    private void placeGridOrders(BacktestState state, Candle candle) {
        BigDecimal currentPrice = candle.getClose();

        rebuildBuyOrdersIfNeeded(state, currentPrice);
        placeBuyOrders(state, currentPrice);
        placeSellOrder(state);
    }

    /**
     * Mirrors {@link StrategyEngine#rebuildBuyOrdersCheck}: if the current price has risen past
     * the trigger price (highest open buy price, marked up by 1.5x the configured profit
     * percent), all open BUY orders are cancelled and their reserved spend refunded.
     */
    private void rebuildBuyOrdersIfNeeded(BacktestState state, BigDecimal currentPrice) {
        List<SimulatedOrder> openBuyOrders = state.openBuyOrders();
        if (openBuyOrders.isEmpty()) {
            return;
        }

        BigDecimal highestBuyPrice = openBuyOrders.stream()
                .map(SimulatedOrder::price)
                .max(BigDecimal::compareTo)
                .orElseThrow();

        BigDecimal percent = state.profitPercent.add(state.profitPercent.divide(BigDecimal.TWO, RoundingMode.HALF_UP));
        BigDecimal multiplier = BigDecimal.ONE.add(percent.divide(ONE_HUNDRED, PRICE_SCALE, RoundingMode.HALF_UP));
        BigDecimal triggerPrice = highestBuyPrice.multiply(multiplier, MathContext.DECIMAL128)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        if (currentPrice.compareTo(triggerPrice) > 0) {
            for (SimulatedOrder order : openBuyOrders) {
                state.wallet = state.wallet.add(order.qty().multiply(order.price()));
                state.openOrders.remove(order);
            }
        }
    }

    /**
     * Mirrors {@link StrategyEngine#placeBuyOrders}: if there are no open BUY orders, places
     * {@code orderCount} new BUY orders below {@code currentPrice}, stepping down by
     * {@link #STEP_FRACTION} per level, as long as the wallet can cover
     * {@code spendPerOrder} for each.
     */
    private void placeBuyOrders(BacktestState state, BigDecimal currentPrice) {
        if (!state.openBuyOrders().isEmpty()) {
            return;
        }

        for (int i = 1; i <= state.orderCount; i++) {
            if (state.wallet.compareTo(state.spendPerOrder) < 0) {
                break;
            }

            BigDecimal levelPrice = currentPrice.multiply(
                    BigDecimal.ONE.subtract(STEP_FRACTION.multiply(BigDecimal.valueOf(i))),
                    MathContext.DECIMAL128
            ).setScale(QTY_SCALE, RoundingMode.HALF_UP);

            BigDecimal qty = state.spendPerOrder.divide(levelPrice, QTY_SCALE, RoundingMode.HALF_UP);

            state.openOrders.add(new SimulatedOrder(OrderType.BUY, levelPrice, qty));
            state.wallet = state.wallet.subtract(state.spendPerOrder);
        }
    }

    /**
     * Mirrors {@link StrategyEngine#placeSellOrder}: places a single SELL order sized to the
     * total quantity of FILL'd BUY orders, priced at their average price marked up by
     * {@code profitPercent}. Any existing open SELL order is replaced (its reserved quantity is
     * not refunded to the wallet, since SELL orders do not reserve cash).
     */
    private void placeSellOrder(BacktestState state) {
        List<SimulatedOrder> filledBuyOrders = state.openOrders.stream()
                .filter(o -> o.type() == OrderType.BUY && o.status() == SimulatedOrderStatus.FILLED)
                .toList();

        if (filledBuyOrders.isEmpty()) {
            return;
        }

        BigDecimal priceSum = filledBuyOrders.stream()
                .map(SimulatedOrder::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal middlePrice = priceSum.divide(BigDecimal.valueOf(filledBuyOrders.size()), PRICE_SCALE, RoundingMode.HALF_UP);

        BigDecimal multiplier = BigDecimal.ONE.add(state.profitPercent.divide(ONE_HUNDRED, PRICE_SCALE, RoundingMode.HALF_UP));
        BigDecimal sellPrice = middlePrice.multiply(multiplier, MathContext.DECIMAL128)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        BigDecimal totalQty = filledBuyOrders.stream()
                .map(SimulatedOrder::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);

        state.openOrders.removeIf(o -> o.type() == OrderType.SELL && o.status() == SimulatedOrderStatus.OPEN);
        state.openOrders.add(new SimulatedOrder(OrderType.SELL, sellPrice, totalQty));
    }

    /**
     * Mutable in-memory state for a single backtest run — the simulated counterpart of a
     * {@link com.vitalys.trading_grid.model.TradingPair} plus its {@link com.vitalys.trading_grid.model.Order}s.
     */
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

        private List<SimulatedOrder> openBuyOrders() {
            return openOrders.stream()
                    .filter(o -> o.type() == OrderType.BUY && o.status() == SimulatedOrderStatus.OPEN)
                    .toList();
        }
    }

    /**
     * In-memory counterpart of {@link com.vitalys.trading_grid.model.Order} — mutable only via
     * {@link #markFilled()} so the fill transition is explicit and auditable.
     */
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
