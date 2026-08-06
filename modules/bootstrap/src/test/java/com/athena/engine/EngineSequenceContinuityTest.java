package com.athena.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.athena.infra.InMemoryIdempotencyStore;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.OrderEventStore;
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
 * The engine sequence must keep counting across restarts.
 *
 * <p>It is the sort key that puts the event log back into decision order. If a fresh process
 * restarts it at zero, new events collide with old ones, {@code ORDER BY engine_sequence}
 * interleaves the two runs, and the next replay rebuilds a book that never existed — from a log
 * where every individual row is perfectly correct.
 */
class EngineSequenceContinuityTest {

  private final List<OrderEvent> log = new CopyOnWriteArrayList<>();
  private final List<Long> assignedSequences = new CopyOnWriteArrayList<>();

  @Test
  @DisplayName("sequences assigned after a restart continue past the ones already in the log")
  void should_not_restart_the_sequence_at_zero() {
    var first = engine();
    try {
      first.place(limitBuy(3050, 100));
      first.place(limitBuy(3040, 200));
    } finally {
      first.stop();
    }

    List<Long> beforeRestart = List.copyOf(assignedSequences);
    assertThat(beforeRestart).isNotEmpty();

    var restarted = engine();
    try {
      restarted.place(limitBuy(3030, 50));
    } finally {
      restarted.stop();
    }

    List<Long> afterRestart =
        assignedSequences.subList(beforeRestart.size(), assignedSequences.size());

    assertThat(afterRestart).isNotEmpty();
    assertThat(afterRestart)
        .as("a reused sequence silently reorders history on the next replay")
        .allSatisfy(seq -> assertThat(seq).isGreaterThan(beforeRestart.getLast()));
  }

  @Test
  @DisplayName("the book still rebuilds correctly after two restarts with writes in between")
  void should_survive_repeated_restarts() {
    var first = engine();
    try {
      first.place(limitBuy(3050, 100));
    } finally {
      first.stop();
    }

    var second = engine();
    try {
      second.place(limitBuy(3100, 50));
    } finally {
      second.stop();
    }

    var third = engine();
    try {
      var book = third.getSnapshot("PETR4").orElseThrow();
      assertThat(book.bids())
          .as("two distinct price levels were placed across two runs")
          .hasSize(2);
      assertThat(book.bids().get(0).price().ticks()).isEqualTo(3100);
      assertThat(book.bids().get(0).totalQuantity().lots()).isEqualTo(50);
      assertThat(book.bids().get(1).price().ticks()).isEqualTo(3050);
      assertThat(book.bids().get(1).totalQuantity().lots()).isEqualTo(100);
    } finally {
      third.stop();
    }
  }

  // ── harness ───────────────────────────────────────────────────────────────────

  private DisruptorMatchingEngine engine() {
    OrderEventStore store =
        new OrderEventStore() {
          @Override
          public void append(long engineSequence, List<OrderEvent> events) {
            assignedSequences.add(engineSequence);
            log.addAll(events);
          }

          @Override
          public List<OrderEvent> loadEvents(OrderId orderId) {
            return List.of();
          }

          @Override
          public List<OrderEvent> loadAll() {
            return new ArrayList<>(log);
          }

          @Override
          public long lastEngineSequence() {
            return assignedSequences.stream().mapToLong(Long::longValue).max().orElse(0L);
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
        UUID.randomUUID().toString(),
        "PETR4",
        OrderSide.BUY,
        OrderType.LIMIT,
        priceTicks,
        lots,
        Instant.now());
  }
}
