package com.athena.engine;

import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.Order;
import com.athena.trading.domain.OrderBook;
import com.athena.trading.domain.OrderBookSnapshot;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.event.OrderEvent;
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
  private final IdempotencyStore idempotencyStore;
  private final MatchingMetrics metrics;

  // Virtual threads for non-blocking I/O dispatch (ADR-003)
  private final Executor ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

  MatchingEventHandler(
      OrderEventStore eventStore,
      DomainEventPublisher eventPublisher,
      IdempotencyStore idempotencyStore,
      MatchingMetrics metrics) {
    this.eventStore = Objects.requireNonNull(eventStore);
    this.eventPublisher = Objects.requireNonNull(eventPublisher);
    this.idempotencyStore = Objects.requireNonNull(idempotencyStore);
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
    Order order = buildOrder(cmd, seq);

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
        "Order matched",
        /* structured args handled by logstash-logback-encoder */ "trades",
        domainEvents.stream().filter(e -> e instanceof TradeExecuted).count());

    String orderId = order.orderId().value().toString();

    // ── I/O — virtual threads ──────────────────────────────────────────────────
    ioExecutor.execute(
        () -> {
          try {
            eventStore.append(domainEvents);
            eventPublisher.publish(domainEvents);
            idempotencyStore.store(cmd.idempotencyKey(), order.orderId());
          } catch (Exception ex) {
            log.error("I/O error persisting order {}", orderId, ex);
          }
        });

    event.placeResult.complete(orderId);
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
    cancelled.ifPresent(
        c -> {
          metrics.recordOrderCancelled(symbol.value());
          snapshotCache.put(symbol, book.snapshot());
          ioExecutor.execute(
              () -> {
                try {
                  eventStore.append(List.of(c));
                  eventPublisher.publish(List.of(c));
                  idempotencyStore.store(cmd.idempotencyKey(), OrderId.of(cmd.orderId()));
                } catch (Exception ex) {
                  log.error("I/O error persisting cancel {}", cmd.orderId(), ex);
                }
              });
        });

    event.cancelResult.complete(cancelled.isPresent());
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

  private static Order buildOrder(PlaceOrderCommand cmd, long seq) {
    Symbol symbol = Symbol.of(cmd.symbol());
    OrderId id = OrderId.generate();
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
