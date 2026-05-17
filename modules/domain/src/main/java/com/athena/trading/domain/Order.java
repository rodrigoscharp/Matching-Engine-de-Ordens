package com.athena.trading.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A resting or in-flight order in the matching engine.
 *
 * <p>Mutable only via package-private methods ({@link #fill} and {@link #cancel}), which are
 * called exclusively by {@link OrderBook}. External code receives read-only views via accessors.
 * This enforces the single-writer invariant without requiring synchronization.
 */
public final class Order {

  private final OrderId orderId;
  private final Symbol symbol;
  private final OrderSide side;
  private final OrderType type;
  private final Optional<Price> limitPrice;
  private final Quantity originalQuantity;
  private final long sequence;
  private final Instant placedAt;
  private final String idempotencyKey;

  // Mutable state — package-private for single-writer access by OrderBook
  long remainingLots;
  OrderStatus status;

  private Order(
      OrderId orderId,
      Symbol symbol,
      OrderSide side,
      OrderType type,
      Optional<Price> limitPrice,
      Quantity originalQuantity,
      long sequence,
      Instant placedAt,
      String idempotencyKey) {
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.symbol = Objects.requireNonNull(symbol, "symbol");
    this.side = Objects.requireNonNull(side, "side");
    this.type = Objects.requireNonNull(type, "type");
    this.limitPrice = Objects.requireNonNull(limitPrice, "limitPrice");
    this.originalQuantity = Objects.requireNonNull(originalQuantity, "originalQuantity");
    if (sequence < 0) throw new IllegalArgumentException("sequence must be >= 0, got: " + sequence);
    this.sequence = sequence;
    this.placedAt = Objects.requireNonNull(placedAt, "placedAt");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.remainingLots = originalQuantity.lots();
    this.status = OrderStatus.OPEN;
  }

  // ── Factory methods ─────────────────────────────────────────────────────────

  public static Order limitBuy(
      OrderId id,
      Symbol symbol,
      Price price,
      Quantity qty,
      long sequence,
      Instant placedAt,
      String idempotencyKey) {
    Objects.requireNonNull(price, "price is required for LIMIT orders");
    return new Order(
        id, symbol, OrderSide.BUY, OrderType.LIMIT,
        Optional.of(price), qty, sequence, placedAt, idempotencyKey);
  }

  public static Order limitSell(
      OrderId id,
      Symbol symbol,
      Price price,
      Quantity qty,
      long sequence,
      Instant placedAt,
      String idempotencyKey) {
    Objects.requireNonNull(price, "price is required for LIMIT orders");
    return new Order(
        id, symbol, OrderSide.SELL, OrderType.LIMIT,
        Optional.of(price), qty, sequence, placedAt, idempotencyKey);
  }

  public static Order marketBuy(
      OrderId id,
      Symbol symbol,
      Quantity qty,
      long sequence,
      Instant placedAt,
      String idempotencyKey) {
    return new Order(
        id, symbol, OrderSide.BUY, OrderType.MARKET,
        Optional.empty(), qty, sequence, placedAt, idempotencyKey);
  }

  public static Order marketSell(
      OrderId id,
      Symbol symbol,
      Quantity qty,
      long sequence,
      Instant placedAt,
      String idempotencyKey) {
    return new Order(
        id, symbol, OrderSide.SELL, OrderType.MARKET,
        Optional.empty(), qty, sequence, placedAt, idempotencyKey);
  }

  // ── Package-private mutation — only OrderBook may call ───────────────────────

  void fill(long lots) {
    if (lots <= 0 || lots > remainingLots) {
      throw new IllegalArgumentException(
          "Cannot fill " + lots + " lots — remaining: " + remainingLots + " on order " + orderId);
    }
    remainingLots -= lots;
    status = (remainingLots == 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
  }

  void cancel() {
    if (status.isTerminal()) {
      throw new IllegalStateException(
          "Cannot cancel order in terminal state " + status + ": " + orderId);
    }
    status = OrderStatus.CANCELLED;
  }

  // ── Read-only accessors ──────────────────────────────────────────────────────

  public OrderId orderId() {
    return orderId;
  }

  public Symbol symbol() {
    return symbol;
  }

  public OrderSide side() {
    return side;
  }

  public OrderType type() {
    return type;
  }

  public Optional<Price> limitPrice() {
    return limitPrice;
  }

  public Quantity originalQuantity() {
    return originalQuantity;
  }

  public Quantity remainingQuantity() {
    return new Quantity(remainingLots);
  }

  public long sequence() {
    return sequence;
  }

  public Instant placedAt() {
    return placedAt;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public OrderStatus status() {
    return status;
  }

  public boolean isOpen() {
    return status.isActive();
  }

  public boolean isFilled() {
    return status == OrderStatus.FILLED;
  }

  public boolean isCancelled() {
    return status == OrderStatus.CANCELLED;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Order other)) return false;
    return orderId.equals(other.orderId);
  }

  @Override
  public int hashCode() {
    return orderId.hashCode();
  }

  @Override
  public String toString() {
    return "Order{"
        + "id=" + orderId
        + ", symbol=" + symbol
        + ", side=" + side
        + ", type=" + type
        + ", price=" + limitPrice.map(Object::toString).orElse("MARKET")
        + ", remaining=" + remainingLots + "/" + originalQuantity.lots() + " lots"
        + ", status=" + status
        + ", seq=" + sequence
        + '}';
  }
}
