package com.vitalys.trading_grid.dto;

/**
 * Result of a historical candle download request.
 *
 * @param symbol     market pair downloaded, e.g. {@code "BTCUSDT"}
 * @param interval   kline interval downloaded, e.g. {@code "1h"}
 * @param savedCount number of new candles persisted (duplicates are skipped)
 */
public record HistoricalDataDownloadResponse(String symbol, String interval, int savedCount) {}
