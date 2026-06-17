package com.vitalys.trading_grid.dto;

import com.vitalys.trading_grid.model.OrderType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single simulated fill recorded during a backtest run.
 *
 * @param time      open time of the candle on which the fill occurred
 * @param type      BUY or SELL
 * @param price     fill price
 * @param qty       filled quantity
 * @param walletAfter wallet balance immediately after this fill was applied
 */
public record BacktestTradeLogEntry(Instant time, OrderType type, BigDecimal price, BigDecimal qty, BigDecimal walletAfter) {}
