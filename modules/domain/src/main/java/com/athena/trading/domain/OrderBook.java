package com.athena.trading.domain;

import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory order book for a single instrument, implementing price-time priority matching.
 *
 * <p><b>Thread safety</b>: NOT thread-safe by design. The LMAX Disruptor single-writer principle
 * (ADR-003) guarantees that only one thread accesses this aggregate at a time. Adding
 * synchronization here would be incorrect.
 *
 * <p><b>Matching rules</b>:
 * <ul>
 *   <li>Price priority: best price executes first (highest bid, lowest ask).
 *   <li>Time priority: within the same price level, earlier orders execute first (FIFO).
 *   <li>Execution price: always the resting (passive) order's price.
 * </ul>
 *
 * <p><b>Market orders</b>: execute against all available liquidity at any price. Unmatched
 * remainder is discarded (no resting allowed).
 */
public final class OrderBook {

  private final Symbol symbol;

  // bids[price] = FIFO queue of buy orders at that price tick
  // Natural order → lastEntry() = best (highest) bid
  private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>();

  // asks[price] = FIFO queue of sell orders at that price tick
  // Natural order → firstEntry() = best (lowest) ask
  private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

  // O(1) lookup for cancel and status queries
  private final Map<OrderId, Order> orderIndex = new HashMap<>();

  private long globalSequence = 0;

  private OrderBook(Symbol symbol) {
    this.symbol = Objects.requireNonNull(symbol, "symbol");
  }

  public static OrderBook forSymbol(Symbol symbol) {
    return new OrderBook(symbol);
  }

  // ── Sequence management ───────────────────────────────────────────────────────

  /** Returns the next monotonically increasing sequence number. Call before creating an Order. */
  public long nextSequence() {
    return ++globalSequence;
  }

  // ── Command: place order ──────────────────────────────────────────────────────

  /**
   * Places an order in the book, matching it immediately if possible.
   *
   * @return immutable list of events: first {@link OrderPlaced}, then zero or more {@link
   *     TradeExecuted}.
   * @throws InvalidOrderException if the order's symbol does not match this book's symbol.
   */
  public List<OrderEvent> place(Order order) {
    if (!order.symbol().equals(symbol)) {
      throw new InvalidOrderException(
          "Order symbol " + order.symbol() + " does not match book symbol " + symbol);
    }
    if (!order.isOpen()) {
      throw new InvalidOrderException("Cannot place non-open order: " + order.status());
    }

    List<OrderEvent> events = new ArrayList<>();
    events.add(
        new OrderPlaced(
            order.orderId(),
            order.symbol(),
            order.side(),
            order.type(),
            order.limitPrice(),
            order.originalQuantity(),
            order.sequence(),
            order.placedAt(),
            order.idempotencyKey(),
            Instant.now()));

    if (order.type().isMarket()) {
      matchMarket(order, events);
    } else {
      matchLimit(order, events);
    }

    // Any remaining quantity rests in the book (limit orders only — market remainder is discarded)
    if (order.isOpen() && order.type().isLimit()) {
      long priceTicks = order.limitPrice().get().ticks();
      NavigableMap<Long, ArrayDeque<Order>> own = order.side().isBuy() ? bids : asks;
      own.computeIfAbsent(priceTicks, k -> new ArrayDeque<>()).offer(order);
      orderIndex.put(order.orderId(), order);
    }

    return List.copyOf(events);
  }

  // ── Command: cancel order ─────────────────────────────────────────────────────

  /**
   * Cancels a resting order, removing it from the book.
   *
   * @return {@link Optional#empty()} if the order does not exist or is already in a terminal state.
   */
  public Optional<OrderCancelled> cancel(OrderId orderId) {
    Order order = orderIndex.get(orderId);
    if (order == null || !order.isOpen()) {
      return Optional.empty();
    }

    NavigableMap<Long, ArrayDeque<Order>> side = order.side().isBuy() ? bids : asks;
    order
        .limitPrice()
        .ifPresent(
            price -> {
              ArrayDeque<Order> queue = side.get(price.ticks());
              if (queue != null) {
                queue.remove(order); // O(n) — acceptable for Sprint 2; lazy-removal in Sprint 5
                if (queue.isEmpty()) {
                  side.remove(price.ticks());
                }
              }
            });

    orderIndex.remove(orderId);
    Quantity cancelledQty = order.remainingQuantity();
    order.cancel();

    return Optional.of(new OrderCancelled(orderId, symbol, cancelledQty, Instant.now()));
  }

  // ── Queries ───────────────────────────────────────────────────────────────────

  public Symbol symbol() {
    return symbol;
  }

  public Optional<Order> findOrder(OrderId orderId) {
    return Optional.ofNullable(orderIndex.get(orderId));
  }

  public Optional<Price> bestBid() {
    return bids.isEmpty() ? Optional.empty() : Optional.of(new Price(bids.lastKey()));
  }

  public Optional<Price> bestAsk() {
    return asks.isEmpty() ? Optional.empty() : Optional.of(new Price(asks.firstKey()));
  }

  public boolean isEmpty() {
    return bids.isEmpty() && asks.isEmpty();
  }

  public int totalBidLevels() {
    return bids.size();
  }

  public int totalAskLevels() {
    return asks.size();
  }

  public int totalRestingOrders() {
    return orderIndex.size();
  }

  /** Produces an immutable snapshot for the query side. Safe to share across threads. */
  public OrderBookSnapshot snapshot() {
    List<OrderBookSnapshot.PriceLevel> bidLevels = new ArrayList<>();
    for (Map.Entry<Long, ArrayDeque<Order>> e : bids.descendingMap().entrySet()) {
      long totalLots = e.getValue().stream().mapToLong(o -> o.remainingLots).sum();
      bidLevels.add(
          new OrderBookSnapshot.PriceLevel(
              new Price(e.getKey()), new Quantity(totalLots), e.getValue().size()));
    }

    List<OrderBookSnapshot.PriceLevel> askLevels = new ArrayList<>();
    for (Map.Entry<Long, ArrayDeque<Order>> e : asks.entrySet()) {
      long totalLots = e.getValue().stream().mapToLong(o -> o.remainingLots).sum();
      askLevels.add(
          new OrderBookSnapshot.PriceLevel(
              new Price(e.getKey()), new Quantity(totalLots), e.getValue().size()));
    }

    return new OrderBookSnapshot(
        symbol, List.copyOf(bidLevels), List.copyOf(askLevels), Instant.now());
  }

  // ── Internal matching ─────────────────────────────────────────────────────────

  private void matchLimit(Order incoming, List<OrderEvent> events) {
    NavigableMap<Long, ArrayDeque<Order>> opposite = incoming.side().isBuy() ? asks : bids;

    while (incoming.isOpen() && !opposite.isEmpty()) {
      Map.Entry<Long, ArrayDeque<Order>> bestEntry =
          incoming.side().isBuy() ? opposite.firstEntry() : opposite.lastEntry();

      long bestPriceTicks = bestEntry.getKey();
      boolean crosses =
          incoming.side().isBuy()
              ? incoming.limitPrice().get().ticks() >= bestPriceTicks
              : incoming.limitPrice().get().ticks() <= bestPriceTicks;

      if (!crosses) break;

      executeAtLevel(incoming, opposite, bestPriceTicks, events);
    }
  }

  private void matchMarket(Order incoming, List<OrderEvent> events) {
    NavigableMap<Long, ArrayDeque<Order>> opposite = incoming.side().isBuy() ? asks : bids;

    while (incoming.isOpen() && !opposite.isEmpty()) {
      Map.Entry<Long, ArrayDeque<Order>> bestEntry =
          incoming.side().isBuy() ? opposite.firstEntry() : opposite.lastEntry();

      executeAtLevel(incoming, opposite, bestEntry.getKey(), events);
    }
  }

  private void executeAtLevel(
      Order incoming,
      NavigableMap<Long, ArrayDeque<Order>> opposite,
      long priceTicks,
      List<OrderEvent> events) {

    ArrayDeque<Order> queue = opposite.get(priceTicks);
    if (queue == null) return;

    while (incoming.isOpen() && !queue.isEmpty()) {
      Order resting = queue.peek();
      long matchedLots = Math.min(incoming.remainingLots, resting.remainingLots);

      incoming.fill(matchedLots);
      resting.fill(matchedLots);

      events.add(
          new TradeExecuted(
              TradeId.generate(),
              symbol,
              incoming.side().isBuy() ? incoming.orderId() : resting.orderId(),
              incoming.side().isBuy() ? resting.orderId() : incoming.orderId(),
              new Price(priceTicks),
              new Quantity(matchedLots),
              Instant.now()));

      if (resting.isFilled()) {
        queue.poll();
        orderIndex.remove(resting.orderId());
      }
    }

    if (queue.isEmpty()) {
      opposite.remove(priceTicks);
    }
  }
}
