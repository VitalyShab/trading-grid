package com.vitalys.trading_grid.service;

import com.vitalys.trading_grid.model.TradingPair;
import com.vitalys.trading_grid.dto.TradingPairRequest;
import com.vitalys.trading_grid.repository.TradingPairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingPairService {

    private final TradingPairRepository tradingPairRepository;

    @Transactional
    public TradingPair createTradingPair(TradingPairRequest request) {
        log.info("Creating trading pair: symbol={}, gateway={}", request.symbol(), request.gateway());

        TradingPair tradingPair = mapToEntity(request);
        TradingPair saved = tradingPairRepository.save(tradingPair);

        log.info("Trading pair created with id={}", saved.getId());
        return saved;
    }

    @Transactional
    public TradingPair updateTradingPair(String symbol, TradingPairRequest request) {
        log.info("Updating trading pair: symbol={}", symbol);

        TradingPair existing = tradingPairRepository.findBySymbol(symbol)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("TradingPair not found: " + symbol));

        existing.setGateway(request.gateway());
        existing.setWallet(request.wallet());
        existing.setActive(request.active());
        existing.setOrderCount(request.orderCount());
        existing.setSpendPerOrder(request.spendPerOrder());

        TradingPair saved = tradingPairRepository.save(existing);
        log.info("Trading pair updated: id={} symbol={}", saved.getId(), saved.getSymbol());
        return saved;
    }

    private TradingPair mapToEntity(TradingPairRequest request) {
        TradingPair tradingPair = new TradingPair();
        tradingPair.setSymbol(request.symbol());
        tradingPair.setGateway(request.gateway());
        tradingPair.setWallet(request.wallet());
        tradingPair.setActive(request.active());
        tradingPair.setOrderCount(request.orderCount());
        tradingPair.setSpendPerOrder(request.spendPerOrder());
        tradingPair.setProfitPercent(request.profitPercent());
        return tradingPair;
    }
}
