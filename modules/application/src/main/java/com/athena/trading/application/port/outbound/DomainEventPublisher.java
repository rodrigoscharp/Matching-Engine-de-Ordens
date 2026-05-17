package com.athena.trading.application.port.outbound;

import com.athena.trading.domain.event.OrderEvent;
import java.util.List;

/**
 * Secondary port: publishes domain events to the message broker (Kafka). Used for Market Data
 * projections and downstream consumers. Guaranteed at-least-once via the outbox pattern.
 */
public interface DomainEventPublisher {

  /** Publishes the events. The adapter guarantees delivery via the outbox pattern. */
  void publish(List<OrderEvent> events);
}
