package com.vitalys.trading_grid.dto;

import java.math.BigDecimal;

public record TradingPairStats(
        String symbol,
        BigDecimal currentWallet,
        BigDecimal initialWallet,
        int completedCycles,
        BigDecimal totalRealizedProfit,
        BigDecimal avgProfitPerCycle,
        int openBuyOrders,
        int filledBuyOrders,
        BigDecimal unrealizedPositionValue,
        BigDecimal currentPrice
) {}
