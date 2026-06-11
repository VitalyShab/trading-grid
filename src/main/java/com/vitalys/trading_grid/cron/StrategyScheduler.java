package com.vitalys.trading_grid.cron;

import com.vitalys.trading_grid.service.StrategyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyScheduler {

    private final StrategyEngine strategyEngine;

    @Scheduled(fixedDelay = 5000)
    public void scheduledTick() {
        strategyEngine.startStrategy();
    }
}
