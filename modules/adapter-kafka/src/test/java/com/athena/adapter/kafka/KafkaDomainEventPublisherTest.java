package com.athena.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.athena.adapter.kafka.config.KafkaTopics;
import com.athena.adapter.kafka.mapper.OrderEventAvroMapper;
import com.athena.adapter.kafka.publisher.KafkaDomainEventPublisher;
import com.athena.trading.avro.OrderCancelledEvent;
import com.athena.trading.avro.OrderPlacedEvent;
import com.athena.trading.avro.TradeExecutedEvent;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.TradeId;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaOperations;

// Lenient: setUp() stubs kafkaOperations.send() globally; some tests don't call it
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaDomainEventPublisherTest {

  // KafkaOperations is an interface — Mockito can always mock interfaces even on JDK 25
  @Mock private KafkaOperations<String, SpecificRecord> kafkaOperations;
  private final OrderEventAvroMapper mapper = new OrderEventAvroMapper();

  private KafkaDomainEventPublisher publisher;

  private static final Symbol PETR4 = Symbol.of("PETR4");
  private static final Instant NOW = Instant.now();

  @BeforeEach
  void setUp() {
    publisher = new KafkaDomainEventPublisher(kafkaOperations, mapper);
    when(kafkaOperations.send(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  void should_publish_order_placed_to_correct_topic_with_symbol_as_key() {
    var event = orderPlacedEvent();

    publisher.publish(List.of(event));

    var topicCaptor = ArgumentCaptor.forClass(String.class);
    var keyCaptor = ArgumentCaptor.forClass(String.class);
    var valueCaptor = ArgumentCaptor.forClass(SpecificRecord.class);
    verify(kafkaOperations).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

    assertThat(topicCaptor.getValue()).isEqualTo(KafkaTopics.ORDER_PLACED);
    assertThat(keyCaptor.getValue()).isEqualTo("PETR4");
    assertThat(valueCaptor.getValue()).isInstanceOf(OrderPlacedEvent.class);
  }

  @Test
  void should_publish_trade_executed_to_correct_topic() {
    var event = tradeExecutedEvent();

    publisher.publish(List.of(event));

    verify(kafkaOperations).send(eq(KafkaTopics.TRADE_EXECUTED), eq("PETR4"), any(TradeExecutedEvent.class));
  }

  @Test
  void should_publish_order_cancelled_to_correct_topic() {
    var event = new OrderCancelled(OrderId.generate(), PETR4, Quantity.of(50), NOW);

    publisher.publish(List.of(event));

    verify(kafkaOperations).send(eq(KafkaTopics.ORDER_CANCELLED), eq("PETR4"), any(OrderCancelledEvent.class));
  }

  @Test
  void should_publish_all_events_in_batch() {
    // Explicit type to avoid List<Record & OrderEvent> inference ambiguity with sealed records
    List<OrderEvent> events = List.of(orderPlacedEvent(), tradeExecutedEvent());

    publisher.publish(events);

    verify(kafkaOperations, times(2)).send(any(), any(), any());
  }

  @Test
  void should_publish_nothing_for_empty_list() {
    publisher.publish(List.of());

    verify(kafkaOperations, never()).send(any(), any(), any());
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  private static OrderPlaced orderPlacedEvent() {
    return new OrderPlaced(
        OrderId.generate(), PETR4, OrderSide.BUY, OrderType.LIMIT,
        Optional.of(Price.of(1050)), Quantity.of(100),
        1L, NOW, "key-1", NOW);
  }

  private static TradeExecuted tradeExecutedEvent() {
    return new TradeExecuted(
        TradeId.generate(), PETR4,
        OrderId.generate(), OrderId.generate(),
        Price.of(1050), Quantity.of(50), NOW);
  }
}
