package com.athena.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.TradeExecuted;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens to the part of a MARKET order that finds no liquidity.
 *
 * <p>A market order never rests, so any unfilled remainder is killed. That is a fact about the
 * client's order, and the client has no other way to learn it — the book emits an event and puts
 * the order in a terminal state rather than dropping the quantity silently.
 */
class MarketOrderRemainderTest {

  private static final Symbol PETR4 = Symbol.of("PETR4");

  @Test
  @DisplayName("an entirely unfillable market order is killed, not silently discarded")
  void should_emit_cancellation_when_no_liquidity_at_all() {
    var book = OrderBook.forSymbol(PETR4);
    var order = marketBuy(book, 100);

    var events = book.place(order);

    assertThat(events).hasAtLeastOneElementOfType(OrderCancelled.class);
    var killed =
        events.stream()
            .filter(OrderCancelled.class::isInstance)
            .map(OrderCancelled.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(killed.orderId()).isEqualTo(order.orderId());
    assertThat(killed.cancelledQuantity().lots()).isEqualTo(100);
  }

  @Test
  @DisplayName("the unfilled remainder of a partially filled market order is killed")
  void should_emit_cancellation_for_the_unfilled_remainder() {
    var book = OrderBook.forSymbol(PETR4);
    book.place(
        Order.limitSell(
            OrderId.generate(), PETR4, Price.of(3050), Quantity.of(30),
            book.nextSequence(), Instant.now(), key()));

    var order = marketBuy(book, 100);
    var events = book.place(order);

    assertThat(events).hasAtLeastOneElementOfType(TradeExecuted.class);
    var killed =
        events.stream()
            .filter(OrderCancelled.class::isInstance)
            .map(OrderCancelled.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(killed.cancelledQuantity().lots())
        .as("70 of the 100 lots found no counterparty")
        .isEqualTo(70);
  }

  @Test
  @DisplayName("a killed market order ends in a terminal state")
  void should_leave_unfilled_market_order_in_terminal_state() {
    var book = OrderBook.forSymbol(PETR4);
    var order = marketBuy(book, 100);

    book.place(order);

    assertThat(order.status().isTerminal())
        .as("an order that can never trade again must not look active")
        .isTrue();
    assertThat(order.isOpen()).isFalse();
  }

  @Test
  @DisplayName("a fully filled market order is not reported as cancelled")
  void should_not_emit_cancellation_when_fully_filled() {
    var book = OrderBook.forSymbol(PETR4);
    book.place(
        Order.limitSell(
            OrderId.generate(), PETR4, Price.of(3050), Quantity.of(100),
            book.nextSequence(), Instant.now(), key()));

    var events = book.place(marketBuy(book, 100));

    assertThat(events).doesNotHaveAnyElementsOfTypes(OrderCancelled.class);
  }

  @Test
  @DisplayName("a killed market order leaves no trace in the book")
  void should_not_rest_a_killed_market_order() {
    var book = OrderBook.forSymbol(PETR4);
    var order = marketBuy(book, 100);

    book.place(order);

    assertThat(book.isEmpty()).isTrue();
    assertThat(book.totalRestingOrders()).isZero();
    assertThat(book.findOrder(order.orderId())).isEmpty();
  }

  private static Order marketBuy(OrderBook book, long lots) {
    return Order.marketBuy(
        OrderId.generate(), PETR4, Quantity.of(lots), book.nextSequence(), Instant.now(), key());
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
