package com.athena.adapter.rest.dto;

import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * REST request to place a new order. Prices and quantities are expressed as human-readable
 * BigDecimal — the controller converts them to ticks/lots (ADR-006).
 *
 * <p>{@code price} is required for LIMIT orders and ignored for MARKET orders. Validated to 2
 * decimal places; instruments with different precision will use instrument config in Sprint 4.
 */
public record PlaceOrderRequest(
    @NotBlank String symbol,
    @NotNull OrderSide side,
    @NotNull OrderType type,
    @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price,
    @NotNull @DecimalMin("1") @Digits(integer = 15, fraction = 0) BigDecimal quantity) {

  public boolean requiresPrice() {
    return type != null && type.isLimit();
  }
}
