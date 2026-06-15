package com.vitalys.trading_grid.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "historical_candle", uniqueConstraints = {
        @UniqueConstraint(name = "uk_historical_candle_symbol_interval_open_time",
                columnNames = {"symbol", "kline_interval", "open_time"})
})
public class HistoricalCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "kline_interval", nullable = false, length = 10)
    private String interval;

    @Column(name = "open_time", nullable = false)
    private Instant openTime;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal open;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal high;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal low;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal close;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal volume;

    @Column(name = "close_time", nullable = false)
    private Instant closeTime;
}
