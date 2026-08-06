package com.athena.engine;

import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.exception.MatchingEngineUnavailableException;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.OrderBookSnapshot;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.event.OrderEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * LMAX Disruptor-based matching engine (ADR-003). Implements all three inbound ports.
 *
 * <h3>Observability</h3>
 * <ul>
 *   <li>Every {@code place()} call is wrapped in a Micrometer {@link Observation} — creates a
 *       distributed trace span (visible in Grafana Tempo) AND records a latency metric.
 *   <li>Ring buffer remaining capacity is a Gauge, scraped by Prometheus every 15s.
 *   <li>Structured logs from {@link MatchingEventHandler} include {@code symbol}, {@code orderId},
 *       {@code traceId}, {@code spanId} via MDC.
 * </ul>
 */
@Component
@Primary
public class DisruptorMatchingEngine
    implements PlaceOrderUseCase, CancelOrderUseCase, GetBookSnapshotUseCase, MatchingEngineStats {

  private static final Logger log = LoggerFactory.getLogger(DisruptorMatchingEngine.class);

  @Value("${athena.engine.ring-buffer-size:4096}")
  private int ringBufferSize;

  @Value("${athena.engine.command-timeout-ms:5000}")
  private long commandTimeoutMs;

  private final MatchingEventHandler handler;
  private final OrderEventStore eventStore;
  private final IdempotencyStore idempotencyStore;
  private final MeterRegistry meterRegistry;
  private final ObservationRegistry observationRegistry;

  private Disruptor<OrderCommandEvent> disruptor;
  private RingBuffer<OrderCommandEvent> ringBuffer;

  public DisruptorMatchingEngine(
      OrderEventStore eventStore,
      DomainEventPublisher eventPublisher,
      IdempotencyStore idempotencyStore,
      MatchingMetrics metrics,
      MeterRegistry meterRegistry,
      ObservationRegistry observationRegistry) {
    this.handler = new MatchingEventHandler(eventStore, eventPublisher, metrics);
    this.eventStore = eventStore;
    this.idempotencyStore = idempotencyStore;
    this.meterRegistry = meterRegistry;
    this.observationRegistry = observationRegistry;
  }

  // ── Lifecycle ────────────────────────────────────────────────────────────────

  @PostConstruct
  public void start() {
    // Rebuild state BEFORE the ring buffer opens. The books live only in memory, so without this
    // a restart silently discards every resting order while its events sit in the event store.
    replayEventLog();

    disruptor =
        new Disruptor<>(
            OrderCommandEvent::new,
            ringBufferSize,
            r -> {
              Thread t = new Thread(r, "matching-engine");
              t.setDaemon(false);
              return t; // Platform thread — CPU-bound, must not park
            },
            ProducerType.MULTI,
            new BlockingWaitStrategy());

    disruptor.handleEventsWith(handler);
    ringBuffer = disruptor.start();

    // Gauge: ring buffer fill level — key metric for backpressure detection
    Gauge.builder("athena.disruptor.buffer.remaining", ringBuffer, RingBuffer::remainingCapacity)
        .description("Remaining slots in the Disruptor ring buffer")
        .register(meterRegistry);
    Gauge.builder("athena.disruptor.buffer.capacity", ringBuffer, RingBuffer::getBufferSize)
        .description("Total capacity of the Disruptor ring buffer")
        .register(meterRegistry);

    log.info("Matching engine started — ring buffer size: {}", ringBufferSize);
  }

  /**
   * Replays the event log into the in-memory books.
   *
   * <p>A failure here is fatal on purpose: starting with a partially rebuilt book would quietly
   * publish a market that does not match the record, and traders would act on it.
   */
  private void replayEventLog() {
    long start = System.nanoTime();
    List<OrderEvent> log = eventStore.loadAll();
    // Resume the sequence even for an empty log — the table may have been truncated of events
    // while a higher watermark remains, and restarting the count would reorder what comes next.
    long resumeFrom = eventStore.lastEngineSequence();
    if (log.isEmpty()) {
      handler.replay(log, resumeFrom);
      this.log.info("Event log empty — starting with fresh books (sequence resumes at {})",
          resumeFrom);
      return;
    }
    int applied = handler.replay(log, resumeFrom);
    this.log.info(
        "Replayed {} events ({} commands) in {} ms — sequence resumes at {}",
        log.size(),
        applied,
        (System.nanoTime() - start) / 1_000_000,
        resumeFrom);
  }

  @PreDestroy
  public void stop() {
    if (disruptor != null) {
      disruptor.shutdown();
      log.info("Matching engine stopped");
    }
  }

  // ── PlaceOrderUseCase ─────────────────────────────────────────────────────────

  @Override
  public String place(PlaceOrderCommand cmd) {
    return Observation.createNotStarted("athena.engine.place", observationRegistry)
        .lowCardinalityKeyValue("symbol", cmd.symbol())
        .lowCardinalityKeyValue("side", cmd.side().name())
        .lowCardinalityKeyValue("type", cmd.type().name())
        .observe(() -> doPlace(cmd));
  }

  // ── CancelOrderUseCase ────────────────────────────────────────────────────────

  @Override
  public boolean cancel(CancelOrderCommand cmd) {
    return Observation.createNotStarted("athena.engine.cancel", observationRegistry)
        .observe(() -> doCancel(cmd));
  }

  // ── GetBookSnapshotUseCase ────────────────────────────────────────────────────

  @Override
  public Optional<OrderBookSnapshot> getSnapshot(String symbol) {
    return handler.snapshotFor(Symbol.of(symbol));
  }

  @Override
  public Map<String, OrderBookSnapshot> getAllSnapshots() {
    Map<String, OrderBookSnapshot> result = new HashMap<>();
    handler.snapshotCache.forEach((sym, snap) -> result.put(sym.value(), snap));
    return Map.copyOf(result);
  }

  // ── Health / metrics access ───────────────────────────────────────────────────

  @Override
  public long ringBufferRemainingCapacity() {
    return ringBuffer != null ? ringBuffer.remainingCapacity() : ringBufferSize;
  }

  @Override
  public long ringBufferCapacity() {
    return ringBuffer != null ? ringBuffer.getBufferSize() : ringBufferSize;
  }

  // ── Internal ─────────────────────────────────────────────────────────────────

  private String doPlace(PlaceOrderCommand cmd) {
    // The OrderId is minted here, on the request thread, so it can be reserved against the
    // idempotency key BEFORE any work is enqueued. A losing racer never reaches the ring buffer.
    OrderId orderId = OrderId.generate();
    if (!idempotencyStore.reserve(cmd.idempotencyKey(), orderId)) {
      String existing = replayedOrderId(cmd.idempotencyKey());
      log.debug("Replayed Idempotency-Key {} — returning original order {}",
          cmd.idempotencyKey(), existing);
      return existing;
    }

    try {
      var result = new CompletableFuture<String>();
      long sequence = ringBuffer.next();
      try {
        OrderCommandEvent event = ringBuffer.get(sequence);
        event.type = OrderCommandEvent.Type.PLACE_ORDER;
        event.placeCommand = cmd;
        event.orderId = orderId;
        event.placeResult = result;
      } finally {
        ringBuffer.publish(sequence);
      }
      return awaitResult(result, "place order [" + cmd.symbol() + "]");
    } catch (MatchingEngineUnavailableException ex) {
      // Deliberately KEEP the reservation. A deadline expiring says nothing about whether the
      // write landed — it may still be in flight and succeed a moment later. Freeing the key here
      // lets the client's retry place a second order for the same intent, which is the worse of
      // the two failures: the cost of holding it is a retry answered with the original orderId.
      throw ex;
    } catch (RuntimeException ex) {
      // A known failure — the order never came to rest, so free the key and let a genuine retry
      // through rather than charging it for work that provably never happened.
      idempotencyStore.release(cmd.idempotencyKey());
      throw ex;
    }
  }

  private boolean doCancel(CancelOrderCommand cmd) {
    OrderId targetOrder = OrderId.of(cmd.orderId());
    if (!idempotencyStore.reserve(cmd.idempotencyKey(), targetOrder)) {
      // This key already ran to completion; re-applying it would report a misleading "not found"
      // for an order this very key already cancelled.
      log.debug("Replayed cancel Idempotency-Key {} — reporting original outcome",
          cmd.idempotencyKey());
      return true;
    }

    try {
      var result = new CompletableFuture<Boolean>();
      long sequence = ringBuffer.next();
      try {
        OrderCommandEvent event = ringBuffer.get(sequence);
        event.type = OrderCommandEvent.Type.CANCEL_ORDER;
        event.cancelCommand = cmd;
        event.cancelResult = result;
      } finally {
        ringBuffer.publish(sequence);
      }
      boolean cancelled = awaitResult(result, "cancel order [" + cmd.orderId() + "]");
      if (!cancelled) {
        idempotencyStore.release(cmd.idempotencyKey());
      }
      return cancelled;
    } catch (MatchingEngineUnavailableException ex) {
      throw ex; // outcome unknown — see doPlace
    } catch (RuntimeException ex) {
      idempotencyStore.release(cmd.idempotencyKey());
      throw ex;
    }
  }

  /**
   * Resolves the winner of a lost reservation race. The winning thread writes the id atomically as
   * part of the reservation, so this lookup is expected to hit; an empty result means the key
   * expired between the two calls, which is indistinguishable from a fresh request.
   */
  private String replayedOrderId(String idempotencyKey) {
    return idempotencyStore
        .find(idempotencyKey)
        .map(id -> id.value().toString())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Idempotency key " + idempotencyKey + " was claimed but has no order id"));
  }

  /**
   * Waits for the matching thread's answer, preserving the failure's identity on the way out.
   *
   * <p>Flattening every cause into one type would erase the distinction the REST layer maps to
   * status codes — a rejected order (422) and an overloaded engine (503) are not the same answer
   * to a client, and neither is a 500.
   */
  private <T> T awaitResult(CompletableFuture<T> future, String operation) {
    try {
      return future.get(commandTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      future.cancel(false);
      throw new MatchingEngineUnavailableException(
          "Matching engine timeout after " + commandTimeoutMs + "ms for: " + operation);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new MatchingEngineUnavailableException("Interrupted waiting for: " + operation, ex);
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime; // domain rejections and the like keep their type
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Error in: " + operation, cause);
    }
  }
}
