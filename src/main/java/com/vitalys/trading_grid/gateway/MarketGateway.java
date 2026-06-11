package com.vitalys.trading_grid.gateway;

import com.vitalys.trading_grid.gateway.dto.Candle;
import com.vitalys.trading_grid.gateway.dto.OrderResponse;
import com.vitalys.trading_grid.gateway.dto.PlaceOrderRequest;

import java.util.List;

/**
 * Exchange-agnostic market connector contract.
 *
 * <p>By programming against this interface — never against {@code BinanceGateway}
 * directly — the grid engine can swap exchanges (Binance → Kraken, live → paper)
 * with zero changes to business logic. This is the Dependency Inversion Principle
 * applied at the infrastructure boundary.
 *
 * <p>All implementations must be thread-safe; the grid engine may call these
 * methods concurrently from multiple grid workers.
 */
public interface MarketGateway {

    /**
     * Fetch historical OHLCV candles for a given symbol and interval.
     *
     * @param symbol   market pair, e.g. {@code "BTCUSDT"}
     * @param interval kline interval, e.g. {@code "1m"}, {@code "1h"}
     * @param limit    number of candles to return (max 1000 on Binance)
     * @return ordered list of candles, oldest first
     */
    List<Candle> getCandles(String symbol, String interval, int limit);

    /**
     * Submit a new limit order to the exchange.
     *
     * @param request fully validated order parameters
     * @return exchange acknowledgement with assigned order ID and status
     */
    OrderResponse placeOrder(PlaceOrderRequest request);

    /**
     * Cancel an open order by its exchange-assigned ID.
     *
     * @param symbol  market pair the order belongs to, e.g. {@code "BTCUSDT"}
     * @param orderId exchange-assigned order identifier
     */
    void cancelOrder(String symbol, String orderId);
}
