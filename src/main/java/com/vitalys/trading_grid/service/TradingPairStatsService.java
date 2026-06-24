package com.vitalys.trading_grid.service;

import com.vitalys.trading_grid.dto.TradingPairStats;
import com.vitalys.trading_grid.model.Order;
import com.vitalys.trading_grid.model.OrderStatus;
import com.vitalys.trading_grid.model.OrderType;
import com.vitalys.trading_grid.model.TradingPair;
import com.vitalys.trading_grid.repository.OrderRepository;
import com.vitalys.trading_grid.repository.TradingPairRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradingPairStatsService {

    private final TradingPairRepository tradingPairRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public TradingPairStats getStats(String symbol) {
        TradingPair pair = tradingPairRepository.findBySymbol(symbol)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("TradingPair not found: " + symbol));

        // Completed sell orders -> realized revenue
        List<Order> completeSells = orderRepository.findByTradingPairIdAndStatusAndType(
                pair.getId(), OrderStatus.COMPLETE, OrderType.SELL);

        // Completed buy orders -> realized cost
        List<Order> completeBuys = orderRepository.findByTradingPairIdAndStatusAndType(
                pair.getId(), OrderStatus.COMPLETE, OrderType.BUY);

        BigDecimal totalRevenue = completeSells.stream()
                .map(o -> o.getPrice().multiply(o.getQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCost = completeBuys.stream()
                .map(o -> o.getPrice().multiply(o.getQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRealizedProfit = totalRevenue.subtract(totalCost);
        int completedCycles = completeSells.size();

        BigDecimal avgProfitPerCycle = completedCycles > 0
                ? totalRealizedProfit.divide(BigDecimal.valueOf(completedCycles), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Open and filled buy orders
        List<Order> openBuys = orderRepository.findByTradingPairIdAndStatusAndType(
                pair.getId(), OrderStatus.OPEN, OrderType.BUY);
        List<Order> filledBuys = orderRepository.findByTradingPairIdAndStatusAndType(
                pair.getId(), OrderStatus.FILL, OrderType.BUY);

        BigDecimal unrealizedPositionValue = filledBuys.stream()
                .map(o -> o.getPrice().multiply(o.getQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TradingPairStats(
                symbol,
                pair.getWallet(),
                null,
                completedCycles,
                totalRealizedProfit,
                avgProfitPerCycle,
                openBuys.size(),
                filledBuys.size(),
                unrealizedPositionValue,
                null
        );
    }
}
