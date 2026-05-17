package com.athena.trading.application.service;

import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.InvalidOrderException;
import com.athena.trading.domain.Order;
import com.athena.trading.domain.OrderBook;
import com.athena.trading.domain.OrderBookSnapshot;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates order placement and cancellation: idempotency check → domain execution → event
 * persistence → event publication.
 *
 * <p><b>Thread safety</b>: The idempotency check uses a thread-safe map; the OrderBook per symbol
 * is accessed exclusively by the matching thread (single-writer principle, ADR-003). In Sprint 5,
 * this service will be replaced by a Disruptor event processor.
 *
 * <p><b>Persistence and Kafka</b>: injected as outbound ports — no direct dependency on Spring or
 * infrastructure. The application layer only knows about interfaces.
 */
public final class TradingApplicationService
    implements PlaceOrderUseCase, CancelOrderUseCase, GetBookSnapshotUseCase {

  private final Map<Symbol, OrderBook> books = new ConcurrentHashMap<>();
  private final OrderEventStore eventStore;
  private final DomainEventPublisher eventPublisher;
  private final IdempotencyStore idempotencyStore;

  public TradingApplicationService(
      OrderEventStore eventStore,
      DomainEventPublisher eventPublisher,
      IdempotencyStore idempotencyStore) {
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
  }

  @Override
  public String place(PlaceOrderCommand cmd) {
    // Idempotency check — return early if already processed
    Optional<OrderId> existing = idempotencyStore.find(cmd.idempotencyKey());
    if (existing.isPresent()) {
      return existing.get().value().toString();
    }

    OrderBook book = bookFor(Symbol.of(cmd.symbol()));
    long seq = book.nextSequence();

    Order order =
        cmd.isMarketOrder()
            ? buildMarketOrder(cmd, seq)
            : buildLimitOrder(cmd, seq);

    List<OrderEvent> events = book.place(order);

    eventStore.append(events);
    eventPublisher.publish(events);
    idempotencyStore.store(cmd.idempotencyKey(), order.orderId());

    return order.orderId().value().toString();
  }

  @Override
  public boolean cancel(CancelOrderCommand cmd) {
    // Idempotency for cancellation — if we already processed this key, it was cancelled
    Optional<OrderId> existing = idempotencyStore.find(cmd.idempotencyKey());
    if (existing.isPresent()) {
      return false; // already processed
    }

    OrderId orderId = OrderId.of(cmd.orderId());
    Symbol symbol = resolveSymbol(orderId);
    if (symbol == null) {
      return false; // order not found in any book
    }

    OrderBook book = books.get(symbol);
    if (book == null) {
      return false;
    }

    Optional<OrderCancelled> cancelled = book.cancel(orderId);
    cancelled.ifPresent(
        event -> {
          eventStore.append(List.of(event));
          eventPublisher.publish(List.of(event));
          idempotencyStore.store(cmd.idempotencyKey(), orderId);
        });

    return cancelled.isPresent();
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

  private Order buildLimitOrder(PlaceOrderCommand cmd, long seq) {
    Symbol symbol = Symbol.of(cmd.symbol());
    OrderId id = OrderId.generate();
    Price price = Price.of(cmd.limitPriceTicks());
    Quantity qty = Quantity.of(cmd.quantityLots());

    return switch (cmd.side()) {
      case BUY -> Order.limitBuy(id, symbol, price, qty, seq, Instant.now(), cmd.idempotencyKey());
      case SELL ->
          Order.limitSell(id, symbol, price, qty, seq, Instant.now(), cmd.idempotencyKey());
    };
  }

  private Order buildMarketOrder(PlaceOrderCommand cmd, long seq) {
    Symbol symbol = Symbol.of(cmd.symbol());
    OrderId id = OrderId.generate();
    Quantity qty = Quantity.of(cmd.quantityLots());

    return switch (cmd.side()) {
      case BUY ->
          Order.marketBuy(id, symbol, qty, seq, Instant.now(), cmd.idempotencyKey());
      case SELL ->
          Order.marketSell(id, symbol, qty, seq, Instant.now(), cmd.idempotencyKey());
    };
  }

  @Override
  public Optional<OrderBookSnapshot> getSnapshot(String symbol) {
    return Optional.ofNullable(books.get(Symbol.of(symbol))).map(OrderBook::snapshot);
  }

  // Package-private for testing — allows injecting a specific book
  void registerBook(OrderBook book) {
    books.put(book.symbol(), book);
  }
}
