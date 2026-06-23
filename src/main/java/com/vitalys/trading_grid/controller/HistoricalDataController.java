package com.vitalys.trading_grid.controller;

import com.vitalys.trading_grid.service.HistoricalDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/historical-data")
@RequiredArgsConstructor
public class HistoricalDataController {

    private static final String DEFAULT_INTERVAL = "1h";

    private final HistoricalDataService historicalDataService;

    @PostMapping("/{symbol}/download")
    public ResponseEntity<Void> downloadLastYear(
            @PathVariable String symbol,
            @RequestParam(defaultValue = DEFAULT_INTERVAL) String interval) {
        log.info("POST /historical-data/{}/download received: interval={}", symbol, interval);

        historicalDataService.downloadLastYearAsync(symbol, interval);

        return ResponseEntity.accepted().build();
    }
}
