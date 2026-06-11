package com.vitalys.trading_grid.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Entity
@Table(name = "trading_pair")
public class TradingPair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 50)
    private String gateway;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal wallet;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int orderCount;

    @Column(precision = 19, scale = 8)
    private BigDecimal spendPerOrder;

    @Column(name = "profit_percent", precision = 5, scale = 2)
    private BigDecimal profitPercent;
}
