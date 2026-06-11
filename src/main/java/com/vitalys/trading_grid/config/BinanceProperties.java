package com.vitalys.trading_grid.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed binding for the {@code spring.web.client.binance} configuration block.
 *
 * <p>{@code @ConfigurationProperties} is far superior to {@code @Value} for structured
 * config: it binds an entire namespace at once, supports JSR-303 validation at startup,
 * and makes all properties visible in a single, testable POJO.
 *
 * <p>{@code @Validated} ensures that if {@code base-url}, {@code api-key}, or
 * {@code api-secret} are blank, the application fails fast at startup rather than
 * crashing at the first API call.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.web.client.binance")
public class BinanceProperties {

    @NotBlank(message = "Binance base-url must not be blank")
    private String baseUrl;

    @NotBlank(message = "Binance api-key must not be blank")
    private String apiKey;

    @NotBlank(message = "Binance api-secret must not be blank")
    private String apiSecret;
}
