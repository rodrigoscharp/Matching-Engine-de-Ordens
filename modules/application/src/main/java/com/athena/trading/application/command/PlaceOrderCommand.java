package com.athena.trading.application.command;

import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import java.time.Instant;
import java.util.Objects;

/**
 * Command to place a new order. Prices and quantities are already in ticks/lots — the adapter is
 * responsible for the BigDecimal-to-ticks conversion (ADR-006).
 *
 * @param idempotencyKey client-generated key; repeating the same key returns the cached result
 * @param symbol instrument identifier (e.g., "PETR4")
 * @param side BUY or SELL
 * @param type LIMIT or MARKET
 * @param limitPriceTicks price in integer ticks (0 means MARKET — use {@code type} to distinguish)
 * @param quantityLots quantity in integer lots
 * @param requestedAt wall-clock time at the client; used for audit, not for ordering
 */
public record PlaceOrderCommand(
    String idempotencyKey,
    String symbol,
    OrderSide side,
    OrderType type,
    long limitPriceTicks,
    long quantityLots,
    Instant requestedAt) {

  public PlaceOrderCommand {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey cannot be blank");
    Objects.requireNonNull(symbol, "symbol");
    if (symbol.isBlank()) throw new IllegalArgumentException("symbol cannot be blank");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(type, "type");
    if (type.isLimit() && limitPriceTicks <= 0)
      throw new IllegalArgumentException("limitPriceTicks must be positive for LIMIT orders");
    if (quantityLots <= 0)
      throw new IllegalArgumentException("quantityLots must be positive, got: " + quantityLots);
    Objects.requireNonNull(requestedAt, "requestedAt");
  }

  public boolean isMarketOrder() {
    return type.isMarket();
  }
}
