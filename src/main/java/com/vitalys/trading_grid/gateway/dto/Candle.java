package com.vitalys.trading_grid.gateway.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable representation of a single OHLCV candlestick (kline).
 *
 * <p>BigDecimal is used for all price/volume fields to preserve decimal precision
 * that would be lost with double arithmetic — critical for financial calculations.
 */
@Value
@Builder
@Jacksonized
public class Candle {

    Instant openTime;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal close;
    BigDecimal volume;
    Instant closeTime;
}
