package com.athena.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athena.infra.InMemoryIdempotencyStore;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.exception.MatchingEngineUnavailableException;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The durability contract of an acknowledgement.
 *
 * <p>A {@code 201 Created} tells a client its order exists. That claim is only true once the
 * order's events have reached the event store — the engine holds no other durable record, so an
 * ack sent ahead of persistence is a promise the system cannot keep across a crash.
 *
 * <p>Kafka publication is deliberately NOT part of that contract: the event store is the source of
 * truth and the circuit breaker already covers broker outages.
 */
class DisruptorMatchingEngineDurabilityTest {

  private final List<OrderEvent> persisted = new CopyOnWriteArrayList<>();
  private final AtomicBoolean eventStoreFails = new AtomicBoolean(false);
  private final AtomicBoolean publisherFails = new AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicLong appendDelayMs =
      new java.util.concurrent.atomic.AtomicLong(0);

  private IdempotencyStore idempotencyStore;
  private DisruptorMatchingEngine engine;

  private void startEngine() {
    idempotencyStore = new InMemoryIdempotencyStore();

    OrderEventStore eventStore =
        new OrderEventStore() {
          @Override
          public void append(long engineSequence, List<OrderEvent> events) {
            long delay = appendDelayMs.get();
            if (delay > 0) {
              try {
                Thread.sleep(delay);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            if (eventStoreFails.get()) {
              throw new IllegalStateException("postgres is down");
            }
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

    DomainEventPublisher publisher =
        events -> {
          if (publisherFails.get()) {
            throw new IllegalStateException("kafka is down");
          }
        };

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
    if (engine != null) engine.stop();
  }

  @Test
  @DisplayName("events are in the event store by the time place() returns")
  void should_persist_events_before_acknowledging() {
    startEngine();

    engine.place(limitBuy("durable-key", 3050, 100));

    assertThat(persisted)
        .as("the ack must not outrun the write it claims to guarantee")
        .anyMatch(e -> e instanceof OrderPlaced);
  }

  @Test
  @DisplayName("a failed persist is reported to the caller instead of a false success")
  void should_not_acknowledge_when_persistence_fails() {
    startEngine();
    eventStoreFails.set(true);

    assertThatThrownBy(() -> engine.place(limitBuy("doomed-key", 3050, 100)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("a failed persist frees the Idempotency-Key so a retry can succeed")
  void should_release_idempotency_key_when_persistence_fails() {
    startEngine();
    eventStoreFails.set(true);

    var cmd = limitBuy("retry-after-failure", 3050, 100);
    assertThatThrownBy(() -> engine.place(cmd)).isInstanceOf(RuntimeException.class);

    eventStoreFails.set(false);
    String orderId = engine.place(cmd);

    assertThat(orderId)
        .as("the first attempt never became durable, so the retry is new work, not a duplicate")
        .isNotBlank();
    assertThat(persisted).anyMatch(e -> e instanceof OrderPlaced);
  }

  @Test
  @DisplayName("a Kafka outage does not fail an order that is already durable")
  void should_acknowledge_even_when_publisher_fails() {
    startEngine();
    publisherFails.set(true);

    String orderId = engine.place(limitBuy("kafka-down-key", 3050, 100));

    assertThat(orderId).isNotBlank();
    assertThat(persisted).anyMatch(e -> e instanceof OrderPlaced);
  }

  @Test
  @DisplayName("a timeout keeps the Idempotency-Key claimed, because the outcome is unknown")
  void should_not_release_idempotency_key_on_timeout() throws Exception {
    startEngine();
    ReflectionTestUtils.setField(engine, "commandTimeoutMs", 1L);
    appendDelayMs.set(250); // persistence outlives the caller's deadline but still succeeds

    var cmd = limitBuy("slow-but-durable", 3050, 100);
    assertThatThrownBy(() -> engine.place(cmd))
        .isInstanceOf(MatchingEngineUnavailableException.class);

    // Let the in-flight write finish, then retry exactly as the 503 tells the client to.
    Thread.sleep(600);
    appendDelayMs.set(0);
    ReflectionTestUtils.setField(engine, "commandTimeoutMs", 5_000L);

    String retried = engine.place(cmd);

    assertThat(idempotencyStore.find("slow-but-durable"))
        .as("releasing a key whose write may have landed lets the retry place a second order")
        .isPresent();
    assertThat(retried)
        .isEqualTo(idempotencyStore.find("slow-but-durable").orElseThrow().value().toString());

    var book = engine.getSnapshot("PETR4").orElseThrow();
    assertThat(book.bids().getFirst().orderCount())
        .as("one client intent must not become two resting orders")
        .isEqualTo(1);
  }

  private static PlaceOrderCommand limitBuy(String key, long priceTicks, long lots) {
    return new PlaceOrderCommand(
        key, "PETR4", OrderSide.BUY, OrderType.LIMIT, priceTicks, lots, Instant.now());
  }
}
