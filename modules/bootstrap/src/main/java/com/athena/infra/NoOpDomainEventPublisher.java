package com.athena.infra;

import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.domain.event.OrderEvent;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * No-op event publisher used in Sprint 3 until the Kafka adapter is implemented in Sprint 4.
 * Events are persisted to the event store but not published to Kafka yet.
 *
 * <p>TODO(Sprint 4): replace with KafkaDomainEventPublisher in adapter-kafka.
 */
@Component
public class NoOpDomainEventPublisher implements DomainEventPublisher {

  @Override
  public void publish(List<OrderEvent> events) {
    // Sprint 4: publish to Kafka via outbox pattern
  }
}
