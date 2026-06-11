package com.vitalys.trading_grid.gateway.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Encapsulates all parameters needed to place a limit order on an exchange.
 *
 * <p>This is an internal gateway DTO — not a REST request body — so we do not
 * annotate it with {@code jakarta.validation} constraints. Validation belongs at
 * the service layer before the gateway is called; the gateway trusts that its
 * caller has already validated the input.
 *
 * <p>Using a value object (immutable) here prevents callers from partially
 * constructing a request. The builder forces you to supply every required field
 * before the object even exists, and the {@code @Value} annotation guarantees
 * that no field can be mutated after construction.
 */
@Value
@Builder
public class PlaceOrderRequest {

    String symbol;
    OrderSide side;

    /**
     * Limit price at which the order should be placed.
     */
    BigDecimal price;

    /**
     * Quantity (base asset amount) for this order.
     */
    BigDecimal quantity;

    public enum OrderSide {
        BUY, SELL
    }
}
