package com.athena.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.athena.infra.InMemoryIdempotencyStore;
import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.OrderBookSnapshot;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.event.OrderEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Surviving a restart.
 *
 * <p>Books exist only in memory. The event store is the sole durable record, so a process that
 * comes back up without replaying it opens a market that silently lost every resting order — while
 * the clients who placed them still believe they are live.
 *
 * <p>Each test runs an engine, keeps the events it produced, then starts a second engine over that
 * same log and compares the two books.
 */
class DisruptorMatchingEngineReplayTest {

  private final List<OrderEvent> log = new CopyOnWriteArrayList<>();

  @Test
  @DisplayName("resting orders come back after a restart")
  void should_restore_resting_orders() {
    OrderBookSnapshot before;
    var first = engine();
    try {
      first.place(limitBuy(3050, 100));
      first.place(limitBuy(3040, 200));
      first.place(limitSell(3080, 75));
      before = first.getSnapshot("PETR4").orElseThrow();
    } finally {
      first.stop();
    }

    var restarted = engine();
    try {
      var after = restarted.getSnapshot("PETR4").orElseThrow();

      assertThat(after.bids()).isEqualTo(before.bids());
      assertThat(after.asks()).isEqualTo(before.asks());
    } finally {
      restarted.stop();
    }
  }

  @Test
  @DisplayName("a partially filled order comes back with only its remaining quantity")
  void should_restore_remaining_quantity_after_a_partial_fill() {
    OrderBookSnapshot before;
    var first = engine();
    try {
      first.place(limitBuy(3050, 100));
      first.place(limitSell(3050, 30)); // eats 30 of the 100
      before = first.getSnapshot("PETR4").orElseThrow();
    } finally {
      first.stop();
    }

    assertThat(before.bids().getFirst().totalQuantity().lots()).isEqualTo(70);

    var restarted = engine();
    try {
      var after = restarted.getSnapshot("PETR4").orElseThrow();

      assertThat(after.bids().getFirst().totalQuantity().lots())
          .as("the fill must be re-derived, not re-applied or forgotten")
          .isEqualTo(70);
      assertThat(after.asks()).isEmpty();
    } finally {
      restarted.stop();
    }
  }

  @Test
  @DisplayName("a cancelled order does not come back to life")
  void should_not_restore_a_cancelled_order() {
    var first = engine();
    try {
      String doomed = first.place(limitBuy(3050, 100));
      first.place(limitBuy(3040, 50));
      first.cancel(new CancelOrderCommand(key(), doomed));
    } finally {
      first.stop();
    }

    var restarted = engine();
    try {
      var after = restarted.getSnapshot("PETR4").orElseThrow();

      assertThat(after.bids()).hasSize(1);
      assertThat(after.bids().getFirst().price().ticks()).isEqualTo(3040);
    } finally {
      restarted.stop();
    }
  }

  @Test
  @DisplayName("an empty log starts a clean engine rather than failing")
  void should_start_clean_when_log_is_empty() {
    var engine = engine();
    try {
      assertThat(engine.getSnapshot("PETR4")).isEmpty();
    } finally {
      engine.stop();
    }
  }

  // ── harness ───────────────────────────────────────────────────────────────────

  /** A fresh engine over the shared, accumulating event log — i.e. a restart. */
  private DisruptorMatchingEngine engine() {
    OrderEventStore store =
        new OrderEventStore() {
          @Override
          public void append(long engineSequence, List<OrderEvent> events) {
            log.addAll(events);
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
            return new ArrayList<>(log);
          }
        };

    var registry = new SimpleMeterRegistry();
    var engine =
        new DisruptorMatchingEngine(
            store,
            (DomainEventPublisher) events -> {},
            new InMemoryIdempotencyStore(),
            new MatchingMetrics(registry),
            registry,
            ObservationRegistry.create());

    ReflectionTestUtils.setField(engine, "ringBufferSize", 1024);
    ReflectionTestUtils.setField(engine, "commandTimeoutMs", 5_000L);
    engine.start();
    return engine;
  }

  private static PlaceOrderCommand limitBuy(long priceTicks, long lots) {
    return new PlaceOrderCommand(
        key(), "PETR4", OrderSide.BUY, OrderType.LIMIT, priceTicks, lots, Instant.now());
  }

  private static PlaceOrderCommand limitSell(long priceTicks, long lots) {
    return new PlaceOrderCommand(
        key(), "PETR4", OrderSide.SELL, OrderType.LIMIT, priceTicks, lots, Instant.now());
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
