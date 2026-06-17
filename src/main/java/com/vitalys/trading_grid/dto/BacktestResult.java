package com.vitalys.trading_grid.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of a backtest run.
 *
 * @param symbol           market pair that was replayed
 * @param interval         kline interval that was replayed
 * @param candlesProcessed number of historical candles the simulation stepped through
 * @param initialWallet    wallet balance at the start of the run
 * @param finalWallet      wallet balance (cash) at the end of the run
 * @param completedCycles  number of completed grid cycles (a SELL fill that closed out
 *                          one or more FILL'd BUY orders)
 * @param totalProfit      sum of profit realised across all completed cycles
 *                         ({@code finalWallet - initialWallet}, excluding any value still
 *                         held in open positions)
 * @param tradeLog         chronological log of every simulated BUY/SELL fill
 */
public record BacktestResult(
        String symbol,
        String interval,
        int candlesProcessed,
        BigDecimal initialWallet,
        BigDecimal finalWallet,
        int completedCycles,
        BigDecimal totalProfit,
        List<BacktestTradeLogEntry> tradeLog
) {}
