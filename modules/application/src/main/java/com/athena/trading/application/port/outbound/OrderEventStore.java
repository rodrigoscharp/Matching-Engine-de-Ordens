package com.athena.trading.application.port.outbound;

import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.event.OrderEvent;
import java.util.List;

/**
 * Secondary port: append-only store for domain events (the source of truth in Event Sourcing).
 * Implemented by {@code adapter-persistence} using PostgreSQL.
 */
public interface OrderEventStore {

  /**
   * Appends events to the store. Must be atomic — all-or-nothing per batch.
   *
   * @param engineSequence position of this batch in the matching engine's decision order. Batches
   *     are written concurrently and therefore land out of order; this is what {@link #loadAll}
   *     sorts by, and without it a replay cannot reproduce the book.
   */
  void append(long engineSequence, List<OrderEvent> events);

  /**
   * Every event naming this order, including trades where it was the counterparty, in insertion
   * order.
   */
  List<OrderEvent> loadEvents(OrderId orderId);

  /** The entire log in engine decision order. Used to rebuild all books at startup. */
  List<OrderEvent> loadAll();

  /**
   * Highest {@code engineSequence} already recorded, or 0 for an empty log.
   *
   * <p>A restarting engine resumes counting from here. Starting over at zero makes new events
   * collide with old ones and silently reorders the log on the next replay.
   */
  long lastEngineSequence();
}
