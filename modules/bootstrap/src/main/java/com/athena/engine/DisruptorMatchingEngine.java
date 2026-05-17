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
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Symbol;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LMAX Disruptor-based matching engine (ADR-003). Implements all three inbound ports as a single
 * Spring component that owns the ring buffer lifecycle.
 *
 * <h3>Pipeline</h3>
 *
 * <pre>
 * REST/gRPC/WS                ring buffer             matching thread
 *   place() ─────publish()───▶ [O|O|O|O|O|O|O] ──▶ MatchingEventHandler
 *   (virtual thread, parks)                               │
 *                                                   virtual-thread pool
 *                                                ┌────────┴──────────┐
 *                                           Postgres              Kafka/Redis
 * </pre>
 *
 * <h3>Concurrency model</h3>
 * <ul>
 *   <li>Multiple producers (REST, gRPC, WS) publish concurrently — {@link ProducerType#MULTI}.
 *   <li>Single consumer ({@link MatchingEventHandler}) runs on one pinned platform thread.
 *   <li>I/O operations run on virtual threads — callers (REST) block on CompletableFuture,
 *       which is cheap because they are also virtual threads.
 * </ul>
 */
@Component
public class DisruptorMatchingEngine
    implements PlaceOrderUseCase, CancelOrderUseCase, GetBookSnapshotUseCase {

  private static final Logger log = LoggerFactory.getLogger(DisruptorMatchingEngine.class);

  @Value("${athena.engine.ring-buffer-size:4096}")
  private int ringBufferSize;

  @Value("${athena.engine.command-timeout-ms:5000}")
  private long commandTimeoutMs;

  private final MatchingEventHandler handler;
  private Disruptor<OrderCommandEvent> disruptor;
  private RingBuffer<OrderCommandEvent> ringBuffer;

  public DisruptorMatchingEngine(
      OrderEventStore eventStore,
      DomainEventPublisher eventPublisher,
      IdempotencyStore idempotencyStore) {
    this.handler = new MatchingEventHandler(eventStore, eventPublisher, idempotencyStore);
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
              return t; // Platform thread — CPU-bound, never parks
            },
            ProducerType.MULTI,
            new BlockingWaitStrategy());

    disruptor.handleEventsWith(handler);
    ringBuffer = disruptor.start();
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

    return awaitResult(result, "place order");
  }

  // ── CancelOrderUseCase ────────────────────────────────────────────────────────

  @Override
  public boolean cancel(CancelOrderCommand cmd) {
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

    return awaitResult(result, "cancel order");
  }

  // ── GetBookSnapshotUseCase ────────────────────────────────────────────────────

  @Override
  public Optional<OrderBookSnapshot> getSnapshot(String symbol) {
    return handler.snapshotFor(Symbol.of(symbol));
  }

  // ── Internal ─────────────────────────────────────────────────────────────────

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
      throw new IllegalStateException("Error in: " + operation, ex);
    }
  }
}
