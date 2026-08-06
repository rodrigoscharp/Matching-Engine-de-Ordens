package com.athena.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.athena.infra.InMemoryIdempotencyStore;
import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.event.OrderEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Idempotency guarantees on the production matching path (the Disruptor engine, which is the
 * {@code @Primary} implementation of every inbound port).
 *
 * <p>The REST API makes {@code Idempotency-Key} mandatory and documents that repeating a request
 * with the same key is safe. These tests hold that contract to the implementation that actually
 * serves traffic.
 */
class DisruptorMatchingEngineIdempotencyTest {

  private final List<OrderEvent> persisted = new CopyOnWriteArrayList<>();
  private IdempotencyStore idempotencyStore;
  private DisruptorMatchingEngine engine;

  @BeforeEach
  void setUp() {
    persisted.clear();
    idempotencyStore = new InMemoryIdempotencyStore();

    OrderEventStore eventStore =
        new OrderEventStore() {
          @Override
          public void append(long engineSequence, List<OrderEvent> events) {
            persisted.addAll(events);
          }

          @Override
          public List<OrderEvent> loadEvents(OrderId orderId) {
            return List.of();
          }

          @Override
          public long lastEngineSequence() {
            return 0L;
          }

          @Override
          public List<OrderEvent> loadAll() {
            return List.of();
          }
        };
    DomainEventPublisher publisher = events -> {};

    var registry = new SimpleMeterRegistry();
    engine =
        new DisruptorMatchingEngine(
            eventStore,
            publisher,
            idempotencyStore,
            new MatchingMetrics(registry),
            registry,
            ObservationRegistry.create());

    ReflectionTestUtils.setField(engine, "ringBufferSize", 1024);
    ReflectionTestUtils.setField(engine, "commandTimeoutMs", 5_000L);
    engine.start();
  }

  @AfterEach
  void tearDown() {
    engine.stop();
  }

  @Test
  @DisplayName("a retry with the same Idempotency-Key returns the original orderId")
  void should_return_original_order_id_on_replayed_key() {
    var cmd = limitBuy("retry-key-1", 3050, 100);

    String first = engine.place(cmd);
    String second = engine.place(cmd);

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("a retry with the same Idempotency-Key does not add a second order to the book")
  void should_not_duplicate_resting_liquidity_on_replayed_key() {
    var cmd = limitBuy("retry-key-2", 3050, 100);

    engine.place(cmd);
    engine.place(cmd);

    var snapshot = engine.getSnapshot("PETR4").orElseThrow();
    assertThat(snapshot.bids()).hasSize(1);
    assertThat(snapshot.bids().getFirst().totalQuantity().lots())
        .as("only the first submission should rest in the book")
        .isEqualTo(100);
    assertThat(snapshot.bids().getFirst().orderCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a distinct Idempotency-Key still places a separate order")
  void should_place_separate_orders_for_distinct_keys() {
    String first = engine.place(limitBuy("key-a", 3050, 100));
    String second = engine.place(limitBuy("key-b", 3050, 100));

    assertThat(second).isNotEqualTo(first);
    var snapshot = engine.getSnapshot("PETR4").orElseThrow();
    assertThat(snapshot.bids().getFirst().totalQuantity().lots()).isEqualTo(200);
  }

  @Test
  @DisplayName("a retry of a cancel with the same Idempotency-Key is not re-applied")
  void should_not_reapply_cancel_on_replayed_key() {
    String orderId = engine.place(limitBuy("key-to-cancel", 3050, 100));

    boolean firstCancel = engine.cancel(new CancelOrderCommand("cancel-key-1", orderId));
    boolean secondCancel = engine.cancel(new CancelOrderCommand("cancel-key-1", orderId));

    assertThat(firstCancel).isTrue();
    assertThat(secondCancel)
        .as("replayed cancel key must report the original outcome, not a fresh attempt")
        .isTrue();
  }

  private static PlaceOrderCommand limitBuy(String key, long priceTicks, long lots) {
    return new PlaceOrderCommand(
        key, "PETR4", OrderSide.BUY, OrderType.LIMIT, priceTicks, lots, Instant.now());
  }
}
