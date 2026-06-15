package com.vitalys.trading_grid.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "trading-grid.historical-data.download")
public class HistoricalDataProperties {

    @Min(value = 1, message = "pageSize must be at least 1")
    private int pageSize = 1000;

    @Min(value = 0, message = "throttleMs must be zero or positive")
    private long throttleMs = 250L;

    private String storageDir = "historical-data";
}
