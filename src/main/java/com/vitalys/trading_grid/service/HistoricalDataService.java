package com.vitalys.trading_grid.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vitalys.trading_grid.config.HistoricalDataProperties;
import com.vitalys.trading_grid.gateway.binance.BinanceGateway;
import com.vitalys.trading_grid.gateway.dto.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(HistoricalDataProperties.class)
public class HistoricalDataService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final BinanceGateway binanceGateway;
    private final HistoricalDataProperties historicalDataProperties;

    @Async
    public void downloadLastYearAsync(String symbol, String interval) {
        try {
            downloadLastYear(symbol, interval);
        } catch (Exception e) {
            log.error("Async historical download failed for {} @ interval={}: {}", symbol, interval, e.getMessage(), e);
        }
    }

    public int downloadLastYear(String symbol, String interval) {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(365));

        log.info("Starting historical download for {} @ interval={} from {} to {}", symbol, interval, start, end);

        int savedCount = 0;
        Instant cursor = start;
        int pageSize = historicalDataProperties.getPageSize();

        while (cursor.isBefore(end)) {
            Instant pageEnd = nextPageEnd(cursor, interval, pageSize, end);

            List<Candle> candles = binanceGateway.getHistoricalKlines(symbol, interval, cursor, pageEnd);
            if (candles.isEmpty()) {
                break;
            }

            savedCount += persistNewCandles(symbol, interval, candles);

            cursor = candles.getLast().getCloseTime().plusMillis(1);

            sleepBetweenPages();
        }

        log.info("Historical download complete for {} @ interval={} — {} new candles saved", symbol, interval, savedCount);
        return savedCount;
    }

    /**
     * Loads the full stored candle series for {@code symbol}/{@code interval} from its JSON
     * file, returning an empty list if no data has been downloaded yet.
     */
    public List<Candle> loadCandles(String symbol, String interval) {
        return readCandles(resolveStorageFile(symbol, interval));
    }

    private int persistNewCandles(String symbol, String interval, List<Candle> candles) {
        Path file = resolveStorageFile(symbol, interval);
        List<Candle> existingCandles = readCandles(file);

        Set<Instant> existingOpenTimes = new HashSet<>();
        for (Candle existing : existingCandles) {
            existingOpenTimes.add(existing.getOpenTime());
        }

        List<Candle> newCandles = new ArrayList<>();
        for (Candle candle : candles) {
            if (existingOpenTimes.add(candle.getOpenTime())) {
                newCandles.add(candle);
            }
        }

        if (newCandles.isEmpty()) {
            return 0;
        }

        List<Candle> mergedCandles = new ArrayList<>(existingCandles);
        mergedCandles.addAll(newCandles);
        writeCandles(file, mergedCandles);

        return newCandles.size();
    }

    private Path resolveStorageFile(String symbol, String interval) {
        Path directory = Path.of(historicalDataProperties.getStorageDir(), symbol);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create historical data directory: " + directory, e);
        }
        return directory.resolve(interval + ".json");
    }

    /**
     * Reads the candles previously stored in {@code file}, returning an empty list if the
     * file does not exist yet.
     */
    private List<Candle> readCandles(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(file.toFile(), new TypeReference<List<Candle>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read historical candle file: " + file, e);
        }
    }

    /**
     * Writes the full set of candles for a symbol/interval pair back to {@code file},
     * overwriting any previous contents.
     */
    private void writeCandles(Path file, List<Candle> candles) {
        try {
            OBJECT_MAPPER.writeValue(file.toFile(), candles);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write historical candle file: " + file, e);
        }
    }

    /**
     * Computes the end of the next page window, capped at {@code overallEnd}.
     *
     * <p>{@link BinanceGateway#getHistoricalKlines} already paginates internally for
     * arbitrarily large ranges, but we still chunk the overall download into windows of
     * roughly {@code pageSize} candles so we can throttle between Binance calls and
     * persist incrementally rather than holding a full year of candles in memory.
     */
    private Instant nextPageEnd(Instant cursor, String interval, int pageSize, Instant overallEnd) {
        long intervalMillis = ChronoUnit.MILLIS.between(Instant.EPOCH, Instant.EPOCH.plus(intervalDuration(interval)));
        Instant windowEnd = cursor.plusMillis(intervalMillis * pageSize);
        return windowEnd.isAfter(overallEnd) ? overallEnd : windowEnd;
    }

    /**
     * Maps a Binance kline interval string (e.g. {@code "1h"}, {@code "15m"}, {@code "1d"})
     * to its {@link java.time.Duration}.
     */
    private java.time.Duration intervalDuration(String interval) {
        char unit = interval.charAt(interval.length() - 1);
        long amount = Long.parseLong(interval.substring(0, interval.length() - 1));
        return switch (unit) {
            case 'm' -> java.time.Duration.ofMinutes(amount);
            case 'h' -> java.time.Duration.ofHours(amount);
            case 'd' -> java.time.Duration.ofDays(amount);
            case 'w' -> java.time.Duration.ofDays(amount * 7);
            default -> throw new IllegalArgumentException("Unsupported kline interval: " + interval);
        };
    }

    /**
     * Sleeps for {@link HistoricalDataProperties#getThrottleMs()} milliseconds between
     * paginated Binance calls to respect rate limits.
     */
    private void sleepBetweenPages() {
        long throttleMs = historicalDataProperties.getThrottleMs();
        if (throttleMs <= 0) {
            return;
        }
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while throttling historical data download", e);
        }
    }
}
