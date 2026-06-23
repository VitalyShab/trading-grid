package com.vitalys.trading_grid.gateway.backtest;

import com.vitalys.trading_grid.gateway.MarketGateway;
import com.vitalys.trading_grid.gateway.dto.Candle;
import com.vitalys.trading_grid.gateway.dto.OrderResponse;
import com.vitalys.trading_grid.gateway.dto.PlaceOrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link MarketGateway} implementation that replays previously downloaded
 * {@link Candle} data (loaded from per-symbol/interval JSON files via
 * {@link com.vitalys.trading_grid.service.HistoricalDataService#loadCandles}) instead of
 * calling a live exchange.
 *
 * <p>This gateway is driven by {@link com.vitalys.trading_grid.service.BacktestService}, which
 * calls {@link #loadSeries} once to load the candle range for a single symbol/interval and then
 * repeatedly calls {@link #advance} to move the simulated clock forward one candle at a time,
 * invoking {@link com.vitalys.trading_grid.service.StrategyEngine#startStrategy()} between each
 * advance.
 *
 * <p><strong>Thread-safety:</strong> unlike {@code BinanceGateway}, this gateway is intentionally
 * stateful — it holds the loaded candle series and the current cursor position as instance
 * fields. Backtests are run synchronously and one at a time (see
 * {@code BacktestService}), so concurrent access is not supported. Calling
 * {@link #loadSeries} again resets the gateway for a new run.
 *
 * <p><strong>Order fill simulation:</strong> {@link com.vitalys.trading_grid.service.StrategyEngine#syncFilledOrders}
 * fetches a "current price" via {@code getCandles(symbol, "1m", 1)} and fills a BUY order if
 * that price is at or below the order price, or a SELL order if it is at or above. To make that
 * unmodified comparison reflect a full candle's price action rather than only its close, this
 * gateway exposes the same comparison directly via {@link #wouldFill}, which
 * {@code BacktestService} uses for its own bookkeeping (trade log / PnL): a BUY fills if the
 * current candle's low reached or went below the order price, and a SELL fills if the candle's
 * high reached or exceeded the order price.
 */
@Slf4j
@Service("backtest")
public class BacktestMarketGateway implements MarketGateway {

    private static final String UNSUPPORTED_NO_SERIES_LOADED =
            "No candle series loaded — call loadSeries() before using the backtest gateway";

    private List<Candle> series = List.of();
    private int cursorIndex = -1;
    private final Map<String, OpenSimulatedOrder> openOrders = new HashMap<>();
    private final AtomicLong orderIdSequence = new AtomicLong(1);

    /**
     * Loads the candle series to replay and resets the cursor to "before the first candle".
     * Call {@link #advance} to move to the first candle.
     */
    public void loadSeries(List<Candle> candles) {
        this.series = List.copyOf(candles);
        this.cursorIndex = -1;
        this.openOrders.clear();
        log.debug("Loaded {} candles into backtest gateway", this.series.size());
    }

    /**
     * Advances the simulated clock to the next candle in the series.
     *
     * @return {@code true} if there was a next candle to advance to, {@code false} if the
     *         series is exhausted
     */
    public boolean advance() {
        if (cursorIndex + 1 >= series.size()) {
            return false;
        }
        cursorIndex++;
        return true;
    }

    /**
     * @return the candle the simulated clock currently points to
     */
    public Candle currentCandle() {
        if (cursorIndex < 0 || cursorIndex >= series.size()) {
            throw new IllegalStateException("Cursor is not positioned on a candle — call advance() first");
        }
        return series.get(cursorIndex);
    }

    /**
     * @return {@code true} if {@link #advance} has produced at least one candle
     */
    public boolean hasCurrentCandle() {
        return cursorIndex >= 0 && cursorIndex < series.size();
    }

    /**
     * Returns the last {@code limit} candles up to and including the cursor. The {@code symbol}
     * and {@code interval} parameters are accepted for interface compatibility but are not used
     * to filter — the loaded series already represents a single symbol/interval.
     */
    @Override
    public List<Candle> getCandles(String symbol, String interval, int limit) {
        if (!hasCurrentCandle()) {
            throw new IllegalStateException(UNSUPPORTED_NO_SERIES_LOADED);
        }

        int fromIndex = Math.max(0, cursorIndex - limit + 1);
        return new ArrayList<>(series.subList(fromIndex, cursorIndex + 1));
    }

    /**
     * Simulates placing a limit order by recording it in the in-memory open-order book and
     * returning an acknowledgement with status {@code "NEW"}. Fill detection happens
     * separately — see the class-level Javadoc on order fill simulation.
     */
    @Override
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        String orderId = "BT-" + orderIdSequence.getAndIncrement();

        var simulatedOrder = new OpenSimulatedOrder(orderId, request);
        openOrders.put(orderId, simulatedOrder);

        log.debug("Backtest gateway recorded {} order id={} symbol={} price={} qty={}",
                request.getSide(), orderId, request.getSymbol(), request.getPrice(), request.getQuantity());

        return OrderResponse.builder()
                .orderId(orderId)
                .clientOrderId(orderId)
                .symbol(request.getSymbol())
                .status("NEW")
                .price(request.getPrice())
                .quantity(request.getQuantity())
                .side(request.getSide())
                .transactTime(currentCandleTime())
                .build();
    }

    /**
     * Removes a simulated order from the open-order book without it being marked as filled.
     */
    @Override
    public void cancelOrder(String symbol, String orderId) {
        OpenSimulatedOrder removed = openOrders.remove(orderId);
        if (removed == null) {
            log.debug("Backtest gateway cancelOrder() — order id={} was not open (already filled or unknown)", orderId);
        } else {
            log.debug("Backtest gateway cancelled order id={} symbol={}", orderId, symbol);
        }
    }

    private Instant currentCandleTime() {
        return hasCurrentCandle() ? series.get(cursorIndex).getCloseTime() : Instant.EPOCH;
    }

    private record OpenSimulatedOrder(String orderId, PlaceOrderRequest request) {
    }
}
