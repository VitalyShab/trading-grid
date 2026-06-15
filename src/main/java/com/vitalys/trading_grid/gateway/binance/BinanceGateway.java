package com.vitalys.trading_grid.gateway.binance;

import tools.jackson.databind.JsonNode;
import com.vitalys.trading_grid.config.BinanceProperties;
import com.vitalys.trading_grid.gateway.MarketGateway;
import com.vitalys.trading_grid.gateway.dto.Candle;
import com.vitalys.trading_grid.gateway.dto.OrderResponse;
import com.vitalys.trading_grid.gateway.dto.PlaceOrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Binance REST API implementation of {@link MarketGateway}.
 *
 * <p>Signing strategy: Binance requires authenticated endpoints to include a
 * {@code timestamp} (epoch millis) and a {@code signature} (HMAC-SHA256 of the
 * full query string using the API secret). The signature must be the LAST
 * parameter in the query string — Binance rejects requests where additional
 * params appear after it.
 *
 * <p>HMAC-SHA256 is computed fresh per request. {@link Mac} instances are NOT
 * thread-safe, so we create one inside each signing call rather than storing it
 * as a field. The overhead is negligible compared to network latency.
 */
@Slf4j
@Service("binance")
public class BinanceGateway implements MarketGateway {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

    // Binance returns at most 1000 klines per request.
    private static final int MAX_KLINES_PER_REQUEST = 1000;

    // Binance kline array field indices — documented at:
    // https://binance-docs.github.io/apidocs/spot/en/#kline-candlestick-data
    private static final int KLINE_OPEN_TIME    = 0;
    private static final int KLINE_OPEN         = 1;
    private static final int KLINE_HIGH         = 2;
    private static final int KLINE_LOW          = 3;
    private static final int KLINE_CLOSE        = 4;
    private static final int KLINE_VOLUME       = 5;
    private static final int KLINE_CLOSE_TIME   = 6;

    private final RestClient restClient;
    private final BinanceProperties properties;

    public BinanceGateway(
            @Qualifier("binanceRestClient") RestClient restClient,
            BinanceProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch OHLCV candles from the public {@code /api/v3/klines} endpoint.
     *
     * <p>No signing required — this endpoint is publicly accessible.
     * Binance returns a JSON array of arrays; we map each inner array to a
     * {@link Candle} using positional indices rather than field names.
     */
    @Override
    public List<Candle> getCandles(String symbol, String interval, int limit) {
        log.debug("Fetching {} candles for {} @ interval={}", limit, symbol, interval);

        var rawKlines = restClient.get()
                .uri("/api/v3/klines?symbol={symbol}&interval={interval}&limit={limit}",
                        symbol, interval, limit)
                .retrieve()
                .body(JsonNode.class);

        return parseKlines(rawKlines);
    }

    /**
     * Fetch a contiguous range of historical klines, paginating through
     * {@code /api/v3/klines} until {@code endTime} is reached.
     *
     * <p>Binance caps each response at {@value #MAX_KLINES_PER_REQUEST} klines, so for
     * ranges that span more candles than that we repeatedly call the endpoint, advancing
     * {@code startTime} to one millisecond past the close time of the last candle returned
     * in the previous page. This avoids duplicate or skipped candles at page boundaries.
     *
     * <p>The loop terminates when a page returns fewer than {@value #MAX_KLINES_PER_REQUEST}
     * candles (i.e. we have reached the end of the available data) or when the next
     * {@code startTime} would be at or past {@code endTime}.
     *
     * @param symbol    market pair, e.g. {@code "BTCUSDT"}
     * @param interval  kline interval, e.g. {@code "1h"}
     * @param startTime inclusive start of the range
     * @param endTime   inclusive end of the range
     * @return ordered list of candles, oldest first, covering the requested range
     */
    public List<Candle> getHistoricalKlines(String symbol, String interval, Instant startTime, Instant endTime) {
        log.debug("Fetching historical klines for {} @ interval={} from {} to {}",
                symbol, interval, startTime, endTime);

        var allCandles = new ArrayList<Candle>();
        long cursorMillis = startTime.toEpochMilli();
        long endMillis = endTime.toEpochMilli();

        while (cursorMillis < endMillis) {
            var rawKlines = restClient.get()
                    .uri("/api/v3/klines?symbol={symbol}&interval={interval}&startTime={startTime}&endTime={endTime}&limit={limit}",
                            symbol, interval, cursorMillis, endMillis, MAX_KLINES_PER_REQUEST)
                    .retrieve()
                    .body(JsonNode.class);

            List<Candle> page = parseKlines(rawKlines);
            if (page.isEmpty()) {
                break;
            }

            allCandles.addAll(page);
            cursorMillis = page.getLast().getCloseTime().toEpochMilli() + 1;

            if (page.size() < MAX_KLINES_PER_REQUEST) {
                break;
            }
        }

        log.debug("Fetched {} historical candles for {} @ interval={}", allCandles.size(), symbol, interval);
        return allCandles;
    }

    /**
     * Place a limit order on {@code /api/v3/order}.
     *
     * <p>The request body is sent as {@code application/x-www-form-urlencoded}
     * (Binance's required content type for POST order). Query params are signed
     * and appended to the URL.
     */
    @Override
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        log.info("Placing {} limit order: symbol={}, price={}, qty={}",
                request.getSide(), request.getSymbol(), request.getPrice(), request.getQuantity());

        // TODO: mocked response for testing — remove to re-enable real Binance call.
        if (true) {
            return OrderResponse.builder()
                    .orderId(String.valueOf(System.currentTimeMillis()))
                    .clientOrderId("mock-client-order-id")
                    .symbol(request.getSymbol())
                    .status("NEW")
                    .price(request.getPrice())
                    .quantity(request.getQuantity())
                    .side(request.getSide())
                    .transactTime(Instant.now())
                    .build();
        }

        var queryString = buildOrderQueryString(request);
        var signature   = sign(queryString);
        var signedQuery = queryString + "&signature=" + signature;

        var raw = restClient.post()
                .uri("/api/v3/order?" + signedQuery)
                .retrieve()
                .body(JsonNode.class);

        return parseOrderResponse(raw);
    }

    /**
     * Cancel an open order on {@code DELETE /api/v3/order}.
     *
     * <p>Binance requires both {@code symbol} and {@code orderId} to uniquely
     * identify an order for cancellation — order IDs are not globally unique,
     * only unique per symbol.
     */
    @Override
    public void cancelOrder(String symbol, String orderId) {
        log.info("Cancelling order: symbol={}, orderId={}", symbol, orderId);

        // TODO: mocked response for testing — remove to re-enable real Binance call.
        if (true) {
            log.info("Order cancelled successfully (mocked): orderId={}", orderId);
            return;
        }

        var queryString = buildCancelQueryString(symbol, orderId);
        var signature   = sign(queryString);
        var signedQuery = queryString + "&signature=" + signature;

        restClient.delete()
                .uri("/api/v3/order?" + signedQuery)
                .retrieve()
                .toBodilessEntity();

        log.info("Order cancelled successfully: orderId={}", orderId);
    }

    // -------------------------------------------------------------------------
    // Query string builders
    // -------------------------------------------------------------------------

    private String buildOrderQueryString(PlaceOrderRequest request) {
        return "symbol="    + request.getSymbol()
             + "&side="     + request.getSide().name()
             + "&type=LIMIT"
             + "&timeInForce=GTC"
             + "&quantity=" + request.getQuantity().toPlainString()
             + "&price="    + request.getPrice().toPlainString()
             + "&timestamp=" + currentTimestamp();
    }

    private String buildCancelQueryString(String symbol, String orderId) {
        return "symbol="    + symbol
             + "&orderId="  + orderId
             + "&timestamp=" + currentTimestamp();
    }

    // -------------------------------------------------------------------------
    // Response parsers
    // -------------------------------------------------------------------------

    private List<Candle> parseKlines(JsonNode root) {
        var candles = new ArrayList<Candle>(root.size());
        for (JsonNode kline : root) {
            candles.add(Candle.builder()
                    .openTime(Instant.ofEpochMilli(kline.get(KLINE_OPEN_TIME).asLong()))
                    .open(new BigDecimal(kline.get(KLINE_OPEN).asString()))
                    .high(new BigDecimal(kline.get(KLINE_HIGH).asString()))
                    .low(new BigDecimal(kline.get(KLINE_LOW).asString()))
                    .close(new BigDecimal(kline.get(KLINE_CLOSE).asString()))
                    .volume(new BigDecimal(kline.get(KLINE_VOLUME).asString()))
                    .closeTime(Instant.ofEpochMilli(kline.get(KLINE_CLOSE_TIME).asLong()))
                    .build());
        }
        log.debug("Parsed {} candles from Binance response", candles.size());
        return candles;
    }

    private OrderResponse parseOrderResponse(JsonNode raw) {
        return OrderResponse.builder()
                .orderId(raw.path("orderId").asString())
                .clientOrderId(raw.path("clientOrderId").asString())
                .symbol(raw.path("symbol").asString())
                .status(raw.path("status").asString())
                .price(new BigDecimal(raw.path("price").asString("0")))
                .quantity(new BigDecimal(raw.path("origQty").asString("0")))
                .side(PlaceOrderRequest.OrderSide.valueOf(raw.path("side").asString()))
                .transactTime(Instant.ofEpochMilli(raw.path("transactTime").asLong()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Signing utilities
    // -------------------------------------------------------------------------

    /**
     * Compute HMAC-SHA256 of {@code data} using the configured API secret,
     * and return the result as a lowercase hex string.
     *
     * <p>A new {@link Mac} instance is created on every call because {@code Mac}
     * is stateful and NOT thread-safe. Do not extract it to a field.
     */
    private String sign(String data) {
        try {
            var secretBytes = properties.getApiSecret().getBytes(StandardCharsets.UTF_8);
            var keySpec     = new SecretKeySpec(secretBytes, HMAC_SHA256_ALGORITHM);
            var mac         = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            mac.init(keySpec);
            var rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * Returns the current wall-clock time in epoch milliseconds.
     *
     * <p>Binance rejects requests where the timestamp differs from their server
     * time by more than 5 000 ms (default recvWindow). Keep system clocks synced.
     */
    private long currentTimestamp() {
        return Instant.now().toEpochMilli();
    }
}
