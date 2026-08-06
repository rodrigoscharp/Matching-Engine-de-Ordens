package com.athena.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athena.infra.InMemoryIdempotencyStore;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.exception.MatchingEngineUnavailableException;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.InvalidOrderException;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.event.OrderEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * How engine failures reach the REST boundary.
 *
 * <p>{@code GlobalExceptionHandler} maps exception types to HTTP status codes. If the engine
 * flattens everything into one generic type on its way out, every failure — a rejected order, an
 * overloaded engine — collapses into a 500 and the handler's careful mapping becomes dead code.
 */
class DisruptorMatchingEngineFailureMappingTest {

  private DisruptorMatchingEngine engine;

  private void startEngine(Consumer<List<OrderEvent>> onAppend, long timeoutMs) {
    OrderEventStore eventStore =
        new OrderEventStore() {
          @Override
          public void append(long engineSequence, List<OrderEvent> events) {
            onAppend.accept(events);
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
            new InMemoryIdempotencyStore(),
            new MatchingMetrics(registry),
            registry,
            ObservationRegistry.create());

    ReflectionTestUtils.setField(engine, "ringBufferSize", 1024);
    ReflectionTestUtils.setField(engine, "commandTimeoutMs", timeoutMs);
    engine.start();
  }

  @AfterEach
  void tearDown() {
    if (engine != null) engine.stop();
  }

  @Test
  @DisplayName("a domain rejection keeps its type so the handler can map it to 422")
  void should_propagate_domain_exception_unwrapped() {
    startEngine(
        events -> {
          throw new InvalidOrderException("order violates a domain rule");
        },
        5_000L);

    assertThatThrownBy(() -> engine.place(limitBuy("domain-fail", 3050, 100)))
        .isInstanceOf(InvalidOrderException.class);
  }

  @Test
  @DisplayName("an engine that misses its deadline is reported as unavailable, not as a bug")
  void should_report_timeout_as_unavailable() {
    startEngine(
        events -> {
          try {
            Thread.sleep(300);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        },
        1L);

    assertThatThrownBy(() -> engine.place(limitBuy("slow-key", 3050, 100)))
        .isInstanceOf(MatchingEngineUnavailableException.class);
  }

  private static PlaceOrderCommand limitBuy(String key, long priceTicks, long lots) {
    return new PlaceOrderCommand(
        key, "PETR4", OrderSide.BUY, OrderType.LIMIT, priceTicks, lots, Instant.now());
  }
}
