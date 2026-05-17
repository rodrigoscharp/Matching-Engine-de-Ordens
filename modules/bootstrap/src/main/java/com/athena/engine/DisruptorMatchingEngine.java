package com.athena.engine;

import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.OrderBookSnapshot;
import com.athena.trading.domain.Symbol;
import java.util.HashMap;
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
    this.handler =
        new MatchingEventHandler(eventStore, eventPublisher, idempotencyStore, metrics);
    this.meterRegistry = meterRegistry;
    this.observationRegistry = observationRegistry;
  }

  // ── Lifecycle ────────────────────────────────────────────────────────────────

  @PostConstruct
  public void start() {
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
    var result = new CompletableFuture<String>();
    long sequence = ringBuffer.next();
    try {
      OrderCommandEvent event = ringBuffer.get(sequence);
      event.type = OrderCommandEvent.Type.PLACE_ORDER;
      event.placeCommand = cmd;
      event.placeResult = result;
    } finally {
      ringBuffer.publish(sequence);
    }
    return awaitResult(result, "place order [" + cmd.symbol() + "]");
  }

  private boolean doCancel(CancelOrderCommand cmd) {
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
    return awaitResult(result, "cancel order [" + cmd.orderId() + "]");
  }

  private <T> T awaitResult(CompletableFuture<T> future, String operation) {
    try {
      return future.get(commandTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      future.cancel(false);
      throw new IllegalStateException(
          "Matching engine timeout after " + commandTimeoutMs + "ms for: " + operation);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for: " + operation, ex);
    } catch (Exception ex) {
      Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
      throw new IllegalStateException("Error in: " + operation, cause);
    }
  }
}
