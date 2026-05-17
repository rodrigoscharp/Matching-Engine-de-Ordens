package com.athena.trading.application.port.outbound;

import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.event.OrderEvent;
import java.util.List;

/**
 * Secondary port: append-only store for domain events (the source of truth in Event Sourcing).
 * Implemented by {@code adapter-persistence} using PostgreSQL.
 */
public interface OrderEventStore {

  /** Appends events to the store. Must be atomic — all-or-nothing per batch. */
  void append(List<OrderEvent> events);

  /** Replays all events for an order in insertion order. Used for state reconstruction. */
  List<OrderEvent> loadEvents(OrderId orderId);
}
