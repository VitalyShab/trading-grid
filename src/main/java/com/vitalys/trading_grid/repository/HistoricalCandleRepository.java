package com.vitalys.trading_grid.repository;

import com.vitalys.trading_grid.model.HistoricalCandle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface HistoricalCandleRepository extends JpaRepository<HistoricalCandle, Long> {

    List<HistoricalCandle> findBySymbolAndIntervalAndOpenTimeBetweenOrderByOpenTimeAsc(
            String symbol, String interval, Instant startInclusive, Instant endInclusive);

    boolean existsBySymbolAndIntervalAndOpenTime(String symbol, String interval, Instant openTime);
}
