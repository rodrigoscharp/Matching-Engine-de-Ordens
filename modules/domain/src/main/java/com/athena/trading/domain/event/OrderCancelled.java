package com.athena.trading.domain.event;

import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import java.time.Instant;
import java.util.Objects;

/** Raised when a resting order is removed from the book on client request. */
public record OrderCancelled(
    OrderId orderId, Symbol symbol, Quantity cancelledQuantity, Instant occurredAt)
    implements OrderEvent {

  public OrderCancelled {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(cancelledQuantity, "cancelledQuantity");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }
}
