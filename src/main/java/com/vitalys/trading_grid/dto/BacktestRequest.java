package com.vitalys.trading_grid.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Parameters for a single backtest run.
 *
 * <p>{@code symbol} and {@code interval} select which previously downloaded
 * {@link com.vitalys.trading_grid.model.HistoricalCandle} series to replay (see
 * {@link com.vitalys.trading_grid.service.HistoricalDataService#downloadLastYear}).
 * {@code from}/{@code to} bound the replay window. The remaining fields configure an
 * isolated, non-persisted {@link com.vitalys.trading_grid.model.TradingPair} — they mirror
 * {@link TradingPairRequest}'s grid parameters.
 */
public record BacktestRequest(

        @NotBlank(message = "Symbol must not be blank")
        String symbol,

        @NotBlank(message = "Interval must not be blank")
        String interval,

        @NotNull(message = "from must not be null")
        Instant from,

        @NotNull(message = "to must not be null")
        Instant to,

        @NotNull(message = "Initial wallet must not be null")
        @Positive(message = "Initial wallet must be a positive value")
        BigDecimal initialWallet,

        @Min(value = 1, message = "orderCount must be at least 1")
        int orderCount,

        @NotNull(message = "spendPerOrder must not be null")
        @Positive(message = "spendPerOrder must be a positive value")
        BigDecimal spendPerOrder,

        @NotNull(message = "profitPercent must not be null")
        @DecimalMin(value = "0.01", message = "profitPercent must be at least 0.01")
        @DecimalMax(value = "999.99", message = "profitPercent must not exceed 999.99")
        BigDecimal profitPercent
) {}
