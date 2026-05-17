package com.athena.trading.domain.event;

import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Raised when an order enters the book for the first time. Contains the full order snapshot so
 * the state can be reconstructed purely from the event log on replay.
 */
public record OrderPlaced(
    OrderId orderId,
    Symbol symbol,
    OrderSide side,
    OrderType type,
    Optional<Price> limitPrice,
    Quantity originalQuantity,
    long sequence,
    Instant placedAt,
    String idempotencyKey,
    Instant occurredAt)
    implements OrderEvent {

  public OrderPlaced {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(limitPrice, "limitPrice");
    Objects.requireNonNull(originalQuantity, "originalQuantity");
    Objects.requireNonNull(placedAt, "placedAt");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
