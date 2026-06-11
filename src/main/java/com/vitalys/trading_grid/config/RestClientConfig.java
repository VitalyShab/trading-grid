package com.vitalys.trading_grid.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Produces a pre-configured {@link RestClient} bean scoped to the Binance API.
 *
 * <p>Centralising RestClient construction here (rather than calling
 * {@code RestClient.create()} inside the gateway) means:
 * <ul>
 *   <li>The base URL and default headers are set once and reused.</li>
 *   <li>Tests can replace this bean with a mock server without touching gateway code.</li>
 *   <li>If we need to add a second exchange, we add a second {@code @Bean} here —
 *       the gateway classes themselves stay unchanged.</li>
 * </ul>
 *
 * <p>The {@code @Qualifier("binanceRestClient")} on the bean name lets Spring
 * inject the right client when multiple RestClient beans coexist.
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(BinanceProperties.class)
public class RestClientConfig {

    private final BinanceProperties binanceProperties;

    @Bean("binanceRestClient")
    public RestClient binanceRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl(binanceProperties.getBaseUrl())
                .defaultHeader("X-MBX-APIKEY", binanceProperties.getApiKey())
                .defaultHeader("Content-Type", "application/x-www-form-urlencoded")
                .build();
    }
}
