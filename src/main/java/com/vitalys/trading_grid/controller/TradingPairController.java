package com.vitalys.trading_grid.controller;

import com.vitalys.trading_grid.model.TradingPair;
import com.vitalys.trading_grid.dto.TradingPairRequest;
import com.vitalys.trading_grid.service.TradingPairService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/trading-pairs")
@RequiredArgsConstructor
public class TradingPairController {

    private final TradingPairService tradingPairService;

    @PostMapping
    public ResponseEntity<TradingPair> createTradingPair(@Valid @RequestBody TradingPairRequest request) {
        log.debug("POST /trading-pairs received: symbol={}", request.symbol());
        TradingPair created = tradingPairService.createTradingPair(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{symbol}")
    public ResponseEntity<TradingPair> updateTradingPair(
            @PathVariable String symbol,
            @Valid @RequestBody TradingPairRequest request) {
        log.debug("PUT /trading-pairs/{} received", symbol);
        TradingPair updated = tradingPairService.updateTradingPair(symbol, request);
        return ResponseEntity.ok(updated);
    }
}
