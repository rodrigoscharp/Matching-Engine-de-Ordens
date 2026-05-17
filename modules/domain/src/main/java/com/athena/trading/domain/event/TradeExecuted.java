package com.athena.trading.domain.event;

import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.TradeId;
import java.time.Instant;
import java.util.Objects;

/**
 * Raised when a buy order and a sell order are matched. The execution price is always the resting
 * (passive) order's price — price-time priority rule.
 *
 * <p>{@code orderId()} returns the aggressor order's id (the order that triggered the match).
 */
public record TradeExecuted(
    TradeId tradeId,
    Symbol symbol,
    OrderId buyOrderId,
    OrderId sellOrderId,
    Price executionPrice,
    Quantity executionQuantity,
    Instant occurredAt)
    implements OrderEvent {

  public TradeExecuted {
    Objects.requireNonNull(tradeId, "tradeId");
    Objects.requireNonNull(symbol, "symbol");
    Objects.requireNonNull(buyOrderId, "buyOrderId");
    Objects.requireNonNull(sellOrderId, "sellOrderId");
    Objects.requireNonNull(executionPrice, "executionPrice");
    Objects.requireNonNull(executionQuantity, "executionQuantity");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  /** Satisfies the OrderEvent contract — returns the buy order id as the primary order reference. */
  @Override
  public OrderId orderId() {
    return buyOrderId;
  }
}
