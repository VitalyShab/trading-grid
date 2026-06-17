package com.vitalys.trading_grid.repository;

import com.vitalys.trading_grid.model.TradingPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradingPairRepository extends JpaRepository<TradingPair, Long> {

    Optional<TradingPair> findBySymbol(String symbol);

    List<TradingPair> findByActiveTrue();

    List<TradingPair> findByGateway(String gateway);

    Iterable<TradingPair> findAllByActiveTrue();

    List<TradingPair> findAllByActiveTrueAndGatewayNot(String gateway);
}
