package com.athena.adapter.kafka.publisher;

import com.athena.adapter.kafka.config.KafkaTopics;
import com.athena.adapter.kafka.mapper.OrderEventAvroMapper;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.List;
import java.util.Objects;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.stereotype.Component;

/**
 * Publishes domain events to Kafka topics using Avro serialization + Confluent Schema Registry.
 *
 * <p>Message key = symbol → all events for the same instrument land in the same partition,
 * preserving ordering guarantees within an instrument.
 *
 * <p>Wrapped in a Resilience4j circuit breaker. If Kafka is unreachable, the circuit opens after
 * the configured failure threshold and events are logged but not lost — the event store (Postgres)
 * is the source of truth and events can be replayed once Kafka recovers.
 */
@Component
public class KafkaDomainEventPublisher implements DomainEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(KafkaDomainEventPublisher.class);
  static final String CIRCUIT_BREAKER_NAME = "kafkaPublisher";

  private final KafkaOperations<String, SpecificRecord> kafkaOperations;
  private final OrderEventAvroMapper mapper;

  public KafkaDomainEventPublisher(
      KafkaOperations<String, SpecificRecord> kafkaOperations, OrderEventAvroMapper mapper) {
    this.kafkaOperations = Objects.requireNonNull(kafkaOperations, "kafkaOperations");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "publishFallback")
  public void publish(List<OrderEvent> events) {
    for (OrderEvent event : events) {
      String topic = topicFor(event);
      String key = event.symbol().value();
      SpecificRecord avro = toAvro(event);
      kafkaOperations
          .send(topic, key, avro)
          .whenComplete(
              (result, ex) -> {
                if (ex != null) {
                  log.error(
                      "Failed to publish event to Kafka",
                      "topic", topic,
                      "orderId", event.orderId(),
                      "error", ex.getMessage());
                }
              });
    }
  }

  // Called by Resilience4j when circuit is open or threshold exceeded
  @SuppressWarnings("unused")
  private void publishFallback(List<OrderEvent> events, Exception ex) {
    log.warn(
        "Kafka circuit breaker open — {} events not published. Events persisted in event store for replay.",
        events.size());
  }

  private static String topicFor(OrderEvent event) {
    return switch (event) {
      case OrderPlaced ignored -> KafkaTopics.ORDER_PLACED;
      case TradeExecuted ignored -> KafkaTopics.TRADE_EXECUTED;
      case OrderCancelled ignored -> KafkaTopics.ORDER_CANCELLED;
    };
  }

  private SpecificRecord toAvro(OrderEvent event) {
    return switch (event) {
      case OrderPlaced e -> mapper.toAvro(e);
      case TradeExecuted e -> mapper.toAvro(e);
      case OrderCancelled e -> mapper.toAvro(e);
    };
  }
}
