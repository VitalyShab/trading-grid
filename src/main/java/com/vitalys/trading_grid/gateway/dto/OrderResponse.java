package com.vitalys.trading_grid.gateway.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Normalised response returned after placing or acknowledging an order.
 *
 * <p>This is our own internal DTO — not the raw Binance JSON — so the rest of
 * the application never depends on Binance's field names. If we later add a
 * second exchange, the same DTO is reused.
 */
@Value
@Builder
public class OrderResponse {

    String orderId;
    String clientOrderId;
    String symbol;
    String status;
    BigDecimal price;
    BigDecimal quantity;
    PlaceOrderRequest.OrderSide side;
    Instant transactTime;
}
