package com.athena.trading.domain.event;

import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Symbol;
import java.time.Instant;

/**
 * Sealed hierarchy of domain events produced by the order book. Every state change in the Trading
 * bounded context is captured as one of these events (Event Sourcing — ADR-002).
 *
 * <p>Events are immutable records. The event store persists them; projections replay them.
 */
public sealed interface OrderEvent permits OrderPlaced, TradeExecuted, OrderCancelled {

  OrderId orderId();

  Symbol symbol();

  Instant occurredAt();
}
