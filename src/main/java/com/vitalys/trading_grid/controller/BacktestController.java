package com.vitalys.trading_grid.controller;

import com.vitalys.trading_grid.dto.BacktestRequest;
import com.vitalys.trading_grid.dto.BacktestResult;
import com.vitalys.trading_grid.service.BacktestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/backtests")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping
    public ResponseEntity<BacktestResult> runBacktest(@Valid @RequestBody BacktestRequest request) {
        log.info("POST /backtests received: symbol={} interval={} from={} to={}",
                request.symbol(), request.interval(), request.from(), request.to());

        BacktestResult result = backtestService.run(request);

        return ResponseEntity.ok(result);
    }
}
