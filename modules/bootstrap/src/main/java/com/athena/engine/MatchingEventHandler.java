package com.athena.engine;

import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.Order;
import com.athena.trading.domain.OrderBook;
import com.athena.trading.domain.OrderBookSnapshot;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import com.lmax.disruptor.EventHandler;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The single-writer matching engine. Runs on one dedicated platform thread — no locking needed.
 *
 * <p>Receives {@link OrderCommandEvent} objects from the ring buffer in strict sequence order.
 * For each event:
 * <ol>
 *   <li>Calls {@link OrderBook#place(Order)} or {@link OrderBook#cancel} (in-memory, microseconds)
 *   <li>Records Micrometer metrics (counters, timer)
 *   <li>Dispatches I/O work (persist + publish + idempotency) to a virtual-thread executor
 *   <li>Completes the caller's {@link java.util.concurrent.CompletableFuture}
 * </ol>
 *
 * <p>The I/O executor uses {@link Executors#newVirtualThreadPerTaskExecutor()} — cheap to park
 * while waiting for Postgres/Kafka/Redis, zero impact on matching latency.
 */
final class MatchingEventHandler implements EventHandler<OrderCommandEvent> {

  private static final Logger log = LoggerFactory.getLogger(MatchingEventHandler.class);

  // Owned exclusively by this thread — no synchronization needed
  private final Map<Symbol, OrderBook> books = new HashMap<>();

  // Read by query side via getSnapshot() — ConcurrentHashMap for safe cross-thread reads
  final ConcurrentHashMap<Symbol, OrderBookSnapshot> snapshotCache = new ConcurrentHashMap<>();

  private final OrderEventStore eventStore;
  private final DomainEventPublisher eventPublisher;
  private final MatchingMetrics metrics;

  // Virtual threads for non-blocking I/O dispatch (ADR-003)
  private final Executor ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

  // Decision order, stamped on every batch. Owned by the matching thread — the I/O threads that
  // actually write the rows finish in an order that has nothing to do with this one.
  private long engineSequence = 0;

  MatchingEventHandler(
      OrderEventStore eventStore,
      DomainEventPublisher eventPublisher,
      MatchingMetrics metrics) {
    this.eventStore = Objects.requireNonNull(eventStore);
    this.eventPublisher = Objects.requireNonNull(eventPublisher);
    this.metrics = Objects.requireNonNull(metrics);
  }

  @Override
  public void onEvent(OrderCommandEvent event, long sequence, boolean endOfBatch) {
    try {
      switch (event.type) {
        case PLACE_ORDER -> processPlace(event);
        case CANCEL_ORDER -> processCancel(event);
      }
    } catch (Exception ex) {
      log.error("Matching error on sequence {}", sequence, ex);
      if (event.placeResult != null) event.placeResult.completeExceptionally(ex);
      if (event.cancelResult != null) event.cancelResult.completeExceptionally(ex);
    } finally {
      MDC.clear();
      event.clear();
    }
  }

  private void processPlace(OrderCommandEvent event) {
    PlaceOrderCommand cmd = event.placeCommand;
    Symbol symbol = Symbol.of(cmd.symbol());
    OrderBook book = bookFor(symbol);
    long seq = book.nextSequence();
    Order order = buildOrder(cmd, event.orderId, seq);

    MDC.put("symbol", symbol.value());
    MDC.put("orderId", order.orderId().value().toString());
    MDC.put("side", cmd.side().name());

    // ── Matching (hot path — timed) ────────────────────────────────────────────
    long start = System.nanoTime();
    List<OrderEvent> domainEvents = book.place(order);
    long durationNanos = System.nanoTime() - start;

    // ── Metrics ────────────────────────────────────────────────────────────────
    metrics.recordOrderAccepted(symbol.value(), cmd.side().name(), cmd.type().name());
    metrics.recordMatchingDuration(durationNanos);
    domainEvents.stream()
        .filter(e -> e instanceof TradeExecuted)
        .map(e -> (TradeExecuted) e)
        .forEach(
            t ->
                metrics.recordTradeExecuted(
                    symbol.value(), t.executionQuantity().lots()));

    snapshotCache.put(symbol, book.snapshot());

    log.debug(
        "Order matched — {} trade(s)",
        domainEvents.stream().filter(e -> e instanceof TradeExecuted).count());

    String orderId = order.orderId().value().toString();
    // Captured before the handler returns — onEvent() clears the ring buffer slot in its finally
    // block, so the lambda below must not read through `event`.
    var result = event.placeResult;
    var context = MDC.getCopyOfContextMap();
    long batchSequence = ++engineSequence;

    // ── I/O — virtual threads ──────────────────────────────────────────────────
    // The matching thread never waits here; only the caller's future does. That keeps the hot
    // path unblocked while still refusing to acknowledge an order that is not yet durable.
    ioExecutor.execute(
        () -> {
          if (context != null) MDC.setContextMap(context);
          try {
            eventStore.append(batchSequence, domainEvents);
            result.complete(orderId);
          } catch (Exception ex) {
            log.error("Failed to persist order {} — rejecting the submission", orderId, ex);
            result.completeExceptionally(ex);
            return;
          } finally {
            MDC.clear();
          }
          // Publication is best-effort and happens after the ack: the event store is the source
          // of truth (see KafkaDomainEventPublisher's circuit breaker).
          try {
            if (context != null) MDC.setContextMap(context);
            eventPublisher.publish(domainEvents);
          } catch (Exception ex) {
            log.error("Failed to publish events for order {}", orderId, ex);
          } finally {
            MDC.clear();
          }
        });
  }

  private void processCancel(OrderCommandEvent event) {
    var cmd = event.cancelCommand;
    Symbol symbol = resolveSymbol(OrderId.of(cmd.orderId()));
    if (symbol == null) {
      event.cancelResult.complete(false);
      return;
    }

    MDC.put("symbol", symbol.value());
    MDC.put("orderId", cmd.orderId());

    OrderBook book = books.get(symbol);
    var cancelled = book.cancel(OrderId.of(cmd.orderId()));
    var result = event.cancelResult;

    if (cancelled.isEmpty()) {
      result.complete(false);
      return;
    }

    var cancelEvent = cancelled.get();
    var orderId = cmd.orderId();
    var context = MDC.getCopyOfContextMap();
    long batchSequence = ++engineSequence;
    metrics.recordOrderCancelled(symbol.value());
    snapshotCache.put(symbol, book.snapshot());

    ioExecutor.execute(
        () -> {
          if (context != null) MDC.setContextMap(context);
          try {
            eventStore.append(batchSequence, List.of(cancelEvent));
            result.complete(true);
          } catch (Exception ex) {
            log.error("Failed to persist cancel {} — rejecting the request", orderId, ex);
            result.completeExceptionally(ex);
            return;
          } finally {
            MDC.clear();
          }
          try {
            if (context != null) MDC.setContextMap(context);
            eventPublisher.publish(List.of(cancelEvent));
          } catch (Exception ex) {
            log.error("Failed to publish cancel for order {}", orderId, ex);
          } finally {
            MDC.clear();
          }
        });
  }

  /**
   * Rebuilds every book from the event log. Called once at startup, before the ring buffer accepts
   * traffic, so no locking is needed despite touching the same state the matching thread owns.
   *
   * <p>Only the commands are replayed — {@link OrderPlaced} and {@link OrderCancelled}. Trades are
   * <em>derived</em> facts: feeding them back in would double-count. Re-running the same commands
   * through the same deterministic matcher reproduces the same trades, which is the property that
   * makes this log a source of truth rather than a diary.
   *
   * @return the number of commands replayed
   */
  int replay(List<OrderEvent> log, long lastEngineSequence) {
    engineSequence = lastEngineSequence;
    int applied = 0;
    for (OrderEvent event : log) {
      switch (event) {
        case OrderPlaced placed -> {
          OrderBook book = bookFor(placed.symbol());
          book.place(rebuildOrder(placed, book.nextSequence()));
          applied++;
        }
        case OrderCancelled cancelled -> {
          OrderBook book = books.get(cancelled.symbol());
          if (book != null) {
            book.cancel(cancelled.orderId());
            applied++;
          }
        }
        case TradeExecuted ignored -> {
          // derived from the placements above — replaying it would book the fill twice
        }
      }
    }
    books.forEach((symbol, book) -> snapshotCache.put(symbol, book.snapshot()));
    return applied;
  }

  /** Rebuilds the order exactly as submitted; the matcher re-derives how much of it filled. */
  private static Order rebuildOrder(OrderPlaced e, long seq) {
    return switch (e.type()) {
      case MARKET ->
          e.side().isBuy()
              ? Order.marketBuy(
                  e.orderId(), e.symbol(), e.originalQuantity(), seq, e.placedAt(),
                  e.idempotencyKey())
              : Order.marketSell(
                  e.orderId(), e.symbol(), e.originalQuantity(), seq, e.placedAt(),
                  e.idempotencyKey());
      case LIMIT ->
          e.side().isBuy()
              ? Order.limitBuy(
                  e.orderId(), e.symbol(), e.limitPrice().orElseThrow(), e.originalQuantity(),
                  seq, e.placedAt(), e.idempotencyKey())
              : Order.limitSell(
                  e.orderId(), e.symbol(), e.limitPrice().orElseThrow(), e.originalQuantity(),
                  seq, e.placedAt(), e.idempotencyKey());
    };
  }

  private OrderBook bookFor(Symbol symbol) {
    return books.computeIfAbsent(symbol, OrderBook::forSymbol);
  }

  private Symbol resolveSymbol(OrderId orderId) {
    for (Map.Entry<Symbol, OrderBook> entry : books.entrySet()) {
      if (entry.getValue().findOrder(orderId).isPresent()) {
        return entry.getKey();
      }
    }
    return null;
  }

  Optional<OrderBookSnapshot> snapshotFor(Symbol symbol) {
    return Optional.ofNullable(snapshotCache.get(symbol));
  }

  private static Order buildOrder(PlaceOrderCommand cmd, OrderId id, long seq) {
    Symbol symbol = Symbol.of(cmd.symbol());
    Quantity qty = Quantity.of(cmd.quantityLots());

    return cmd.isMarketOrder()
        ? switch (cmd.side()) {
          case BUY -> Order.marketBuy(id, symbol, qty, seq, Instant.now(), cmd.idempotencyKey());
          case SELL -> Order.marketSell(id, symbol, qty, seq, Instant.now(), cmd.idempotencyKey());
        }
        : switch (cmd.side()) {
          case BUY ->
              Order.limitBuy(
                  id, symbol, Price.of(cmd.limitPriceTicks()), qty,
                  seq, Instant.now(), cmd.idempotencyKey());
          case SELL ->
              Order.limitSell(
                  id, symbol, Price.of(cmd.limitPriceTicks()), qty,
                  seq, Instant.now(), cmd.idempotencyKey());
        };
  }
}
