package com.vitalys.trading_grid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradingGridApplication {

    static void main(String[] args) {
        SpringApplication.run(TradingGridApplication.class, args);
    }

}
