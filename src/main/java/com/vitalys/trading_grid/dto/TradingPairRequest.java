package com.vitalys.trading_grid.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record TradingPairRequest(

        @NotBlank(message = "Symbol must not be blank")
        String symbol,

        @NotBlank(message = "Gateway must not be blank")
        String gateway,

        @NotNull(message = "Wallet must not be null")
        @Positive(message = "Wallet must be a positive value")
        BigDecimal wallet,

        boolean active,

        @Min(value = 0, message = "orderCount must be zero or positive")
        int orderCount,

        @Positive(message = "spendPerOrder must be a positive value")
        BigDecimal spendPerOrder,

        @DecimalMin(value = "0.01", message = "profitPercent must be at least 0.01")
        @DecimalMax(value = "999.99", message = "profitPercent must not exceed 999.99")
        BigDecimal profitPercent,

        @DecimalMin(value = "0.0", message = "feePercent must be non-negative")
        @DecimalMax(value = "10.0", message = "feePercent must not exceed 10")
        BigDecimal feePercent
) {}
