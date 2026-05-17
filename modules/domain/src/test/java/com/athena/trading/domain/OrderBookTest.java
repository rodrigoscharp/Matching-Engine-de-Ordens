package com.athena.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Specification-level tests for the order book matching engine. Each test exercises a single
 * matching scenario to make failures immediately understandable.
 */
class OrderBookTest {

  private static final Symbol PETR4 = Symbol.of("PETR4");

  private OrderBook book;

  @BeforeEach
  void setUp() {
    book = OrderBook.forSymbol(PETR4);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private Order limitBuy(long priceTicks, long lots) {
    return Order.limitBuy(
        OrderId.generate(), PETR4, Price.of(priceTicks), Quantity.of(lots),
        book.nextSequence(), Instant.now(), "b-" + System.nanoTime());
  }

  private Order limitSell(long priceTicks, long lots) {
    return Order.limitSell(
        OrderId.generate(), PETR4, Price.of(priceTicks), Quantity.of(lots),
        book.nextSequence(), Instant.now(), "s-" + System.nanoTime());
  }

  private Order marketBuy(long lots) {
    return Order.marketBuy(
        OrderId.generate(), PETR4, Quantity.of(lots),
        book.nextSequence(), Instant.now(), "mb-" + System.nanoTime());
  }

  private Order marketSell(long lots) {
    return Order.marketSell(
        OrderId.generate(), PETR4, Quantity.of(lots),
        book.nextSequence(), Instant.now(), "ms-" + System.nanoTime());
  }

  private static List<TradeExecuted> tradesFrom(List<OrderEvent> events) {
    return events.stream().filter(e -> e instanceof TradeExecuted).map(e -> (TradeExecuted) e).toList();
  }

  // ── Resting in book (no crossing orders) ────────────────────────────────────

  @Nested
  class WhenNoOpposingOrders {

    @Test
    void should_add_buy_to_book_when_no_asks_exist() {
      var events = book.place(limitBuy(100, 50));

      assertThat(tradesFrom(events)).isEmpty();
      assertThat(book.totalRestingOrders()).isEqualTo(1);
      assertThat(book.bestBid()).contains(Price.of(100));
      assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void should_add_sell_to_book_when_no_bids_exist() {
      var events = book.place(limitSell(100, 50));

      assertThat(tradesFrom(events)).isEmpty();
      assertThat(book.totalRestingOrders()).isEqualTo(1);
      assertThat(book.bestAsk()).contains(Price.of(100));
      assertThat(book.bestBid()).isEmpty();
    }

    @Test
    void should_emit_order_placed_event() {
      var order = limitBuy(100, 50);
      var events = book.place(order);

      assertThat(events).hasSize(1);
      assertThat(events.getFirst()).isInstanceOf(OrderPlaced.class);
      var placed = (OrderPlaced) events.getFirst();
      assertThat(placed.orderId()).isEqualTo(order.orderId());
      assertThat(placed.side()).isEqualTo(OrderSide.BUY);
    }
  }

  // ── No crossing (price too far) ──────────────────────────────────────────────

  @Nested
  class WhenPricesDoNotCross {

    @Test
    void should_not_match_buy_when_bid_price_below_ask() {
      book.place(limitSell(101, 100)); // ask at 101

      var events = book.place(limitBuy(100, 100)); // bid at 100 — does not cross

      assertThat(tradesFrom(events)).isEmpty();
      assertThat(book.totalRestingOrders()).isEqualTo(2);
    }

    @Test
    void should_not_match_sell_when_ask_price_above_bid() {
      book.place(limitBuy(99, 100)); // bid at 99

      var events = book.place(limitSell(100, 100)); // ask at 100 — does not cross

      assertThat(tradesFrom(events)).isEmpty();
      assertThat(book.totalRestingOrders()).isEqualTo(2);
    }
  }

  // ── Exact match ──────────────────────────────────────────────────────────────

  @Nested
  class WhenExactMatch {

    @Test
    void should_fully_fill_both_orders_when_quantities_equal() {
      book.place(limitSell(100, 200));

      var events = book.place(limitBuy(100, 200));
      var trades = tradesFrom(events);

      assertThat(trades).hasSize(1);
      assertThat(trades.getFirst().executionQuantity()).isEqualTo(Quantity.of(200));
      assertThat(trades.getFirst().executionPrice()).isEqualTo(Price.of(100));
      assertThat(book.totalRestingOrders()).isZero();
      assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void should_execute_at_resting_sell_price_when_buy_aggresses() {
      book.place(limitSell(99, 100));  // resting ask at 99

      var events = book.place(limitBuy(105, 100)); // aggressive buy at 105

      var trades = tradesFrom(events);
      assertThat(trades).hasSize(1);
      // Price-time priority: execution price = resting (passive) order's price
      assertThat(trades.getFirst().executionPrice()).isEqualTo(Price.of(99));
    }

    @Test
    void should_execute_at_resting_buy_price_when_sell_aggresses() {
      book.place(limitBuy(105, 100)); // resting bid at 105

      var events = book.place(limitSell(99, 100)); // aggressive sell at 99

      var trades = tradesFrom(events);
      assertThat(trades).hasSize(1);
      assertThat(trades.getFirst().executionPrice()).isEqualTo(Price.of(105));
    }

    @Test
    void should_assign_buy_order_id_and_sell_order_id_correctly() {
      var sell = limitSell(100, 50);
      var buy = limitBuy(100, 50);
      book.place(sell);

      var trades = tradesFrom(book.place(buy));

      assertThat(trades.getFirst().buyOrderId()).isEqualTo(buy.orderId());
      assertThat(trades.getFirst().sellOrderId()).isEqualTo(sell.orderId());
    }
  }

  // ── Partial fills ────────────────────────────────────────────────────────────

  @Nested
  class WhenPartialFill {

    @Test
    void should_partially_fill_buy_when_sell_has_less_quantity() {
      book.place(limitSell(100, 30)); // ask: 30 lots

      var events = book.place(limitBuy(100, 100)); // buy: 100 lots
      var trades = tradesFrom(events);

      assertThat(trades).hasSize(1);
      assertThat(trades.getFirst().executionQuantity()).isEqualTo(Quantity.of(30));
      // Remaining 70 lots of the buy should rest in book
      assertThat(book.totalRestingOrders()).isEqualTo(1);
      assertThat(book.bestBid()).contains(Price.of(100));
      assertThat(book.bestAsk()).isEmpty();
    }

    @Test
    void should_partially_fill_sell_when_buy_has_less_quantity() {
      book.place(limitBuy(100, 30)); // bid: 30 lots

      var events = book.place(limitSell(100, 100)); // sell: 100 lots
      var trades = tradesFrom(events);

      assertThat(trades).hasSize(1);
      assertThat(trades.getFirst().executionQuantity()).isEqualTo(Quantity.of(30));
      assertThat(book.totalRestingOrders()).isEqualTo(1);
      assertThat(book.bestAsk()).contains(Price.of(100));
      assertThat(book.bestBid()).isEmpty();
    }

    @Test
    void should_emit_partially_filled_status_on_resting_order() {
      var resting = limitSell(100, 100);
      book.place(resting);

      book.place(limitBuy(100, 40)); // consume 40 of 100

      assertThat(resting.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
      assertThat(resting.remainingQuantity()).isEqualTo(Quantity.of(60));
    }
  }

  // ── Multi-level matching ─────────────────────────────────────────────────────

  @Nested
  class WhenMultiplePriceLevels {

    @Test
    void should_consume_multiple_ask_levels_for_large_buy() {
      book.place(limitSell(100, 50)); // best ask
      book.place(limitSell(101, 50)); // second ask
      book.place(limitSell(102, 50)); // third ask

      var trades = tradesFrom(book.place(limitBuy(102, 120)));

      // Consumes 50 @ 100, 50 @ 101, 20 @ 102
      assertThat(trades).hasSize(3);
      assertThat(trades.get(0).executionPrice()).isEqualTo(Price.of(100));
      assertThat(trades.get(0).executionQuantity()).isEqualTo(Quantity.of(50));
      assertThat(trades.get(1).executionPrice()).isEqualTo(Price.of(101));
      assertThat(trades.get(1).executionQuantity()).isEqualTo(Quantity.of(50));
      assertThat(trades.get(2).executionPrice()).isEqualTo(Price.of(102));
      assertThat(trades.get(2).executionQuantity()).isEqualTo(Quantity.of(20));
      // 30 lots remain resting at 102
      assertThat(book.totalRestingOrders()).isEqualTo(1);
      assertThat(book.bestAsk()).contains(Price.of(102));
    }

    @Test
    void should_consume_multiple_bid_levels_for_large_sell() {
      book.place(limitBuy(102, 50)); // best bid
      book.place(limitBuy(101, 50)); // second bid
      book.place(limitBuy(100, 50)); // third bid

      var trades = tradesFrom(book.place(limitSell(100, 120)));

      assertThat(trades).hasSize(3);
      assertThat(trades.get(0).executionPrice()).isEqualTo(Price.of(102));
      assertThat(trades.get(0).executionQuantity()).isEqualTo(Quantity.of(50));
      assertThat(trades.get(1).executionPrice()).isEqualTo(Price.of(101));
      assertThat(trades.get(2).executionPrice()).isEqualTo(Price.of(100));
      assertThat(trades.get(2).executionQuantity()).isEqualTo(Quantity.of(20));
      assertThat(book.totalRestingOrders()).isEqualTo(1);
    }
  }

  // ── Price-time priority within same level ────────────────────────────────────

  @Nested
  class WhenPriceTimePriority {

    @Test
    void should_execute_earlier_order_first_at_same_price_level() {
      var first = limitSell(100, 50);  // arrives first
      var second = limitSell(100, 50); // arrives second (same price)
      book.place(first);
      book.place(second);

      var trades = tradesFrom(book.place(limitBuy(100, 50)));

      // Only first should be matched
      assertThat(trades).hasSize(1);
      assertThat(trades.getFirst().sellOrderId()).isEqualTo(first.orderId());
      assertThat(book.totalRestingOrders()).isEqualTo(1);
      assertThat(book.findOrder(second.orderId())).isPresent();
      assertThat(book.findOrder(first.orderId())).isEmpty(); // was fully consumed
    }
  }

  // ── Market orders ─────────────────────────────────────────────────────────────

  @Nested
  class WhenMarketOrder {

    @Test
    void should_execute_market_buy_against_all_asks_at_any_price() {
      book.place(limitSell(100, 30));
      book.place(limitSell(200, 30)); // very different price

      var trades = tradesFrom(book.place(marketBuy(60)));

      assertThat(trades).hasSize(2);
      assertThat(trades.get(0).executionPrice()).isEqualTo(Price.of(100));
      assertThat(trades.get(1).executionPrice()).isEqualTo(Price.of(200));
      assertThat(book.isEmpty()).isTrue();
    }

    @Test
    void should_execute_market_sell_against_all_bids() {
      book.place(limitBuy(200, 30));
      book.place(limitBuy(100, 30));

      var trades = tradesFrom(book.place(marketSell(60)));

      assertThat(trades).hasSize(2);
      assertThat(trades.get(0).executionPrice()).isEqualTo(Price.of(200)); // best bid first
      assertThat(trades.get(1).executionPrice()).isEqualTo(Price.of(100));
    }

    @Test
    void should_discard_unfilled_market_order_when_liquidity_exhausted() {
      book.place(limitSell(100, 10)); // only 10 lots available

      var trades = tradesFrom(book.place(marketBuy(100))); // wants 100

      assertThat(trades).hasSize(1);
      assertThat(trades.getFirst().executionQuantity()).isEqualTo(Quantity.of(10));
      // Market order remainder is NOT added to book
      assertThat(book.totalRestingOrders()).isZero();
      assertThat(book.isEmpty()).isTrue();
    }
  }

  // ── Cancel ───────────────────────────────────────────────────────────────────

  @Nested
  class WhenCancel {

    @Test
    void should_remove_order_from_book_on_cancel() {
      var order = limitBuy(100, 50);
      book.place(order);

      Optional<OrderCancelled> result = book.cancel(order.orderId());

      assertThat(result).isPresent();
      assertThat(result.get().cancelledQuantity()).isEqualTo(Quantity.of(50));
      assertThat(book.totalRestingOrders()).isZero();
      assertThat(book.bestBid()).isEmpty();
    }

    @Test
    void should_return_empty_when_cancelling_non_existent_order() {
      var result = book.cancel(OrderId.generate());
      assertThat(result).isEmpty();
    }

    @Test
    void should_return_remaining_quantity_when_cancelling_partially_filled_order() {
      var sell = limitSell(100, 100);
      book.place(sell);
      book.place(limitBuy(100, 40)); // partially fill: 40 matched, 60 remain

      Optional<OrderCancelled> result = book.cancel(sell.orderId());

      assertThat(result).isPresent();
      assertThat(result.get().cancelledQuantity()).isEqualTo(Quantity.of(60));
    }

    @Test
    void should_mark_order_as_cancelled_after_cancel() {
      var order = limitBuy(100, 50);
      book.place(order);
      book.cancel(order.orderId());

      assertThat(order.isCancelled()).isTrue();
      assertThat(order.isOpen()).isFalse();
    }
  }

  // ── Snapshot ─────────────────────────────────────────────────────────────────

  @Nested
  class WhenSnapshot {

    @Test
    void should_produce_snapshot_with_correct_levels() {
      book.place(limitBuy(102, 100));
      book.place(limitBuy(101, 200));
      book.place(limitSell(103, 150));

      var snap = book.snapshot();

      assertThat(snap.bids()).hasSize(2);
      assertThat(snap.bids().getFirst().price()).isEqualTo(Price.of(102)); // highest bid first
      assertThat(snap.bids().getFirst().totalQuantity()).isEqualTo(Quantity.of(100));
      assertThat(snap.asks()).hasSize(1);
      assertThat(snap.asks().getFirst().price()).isEqualTo(Price.of(103));
    }

    @Test
    void should_aggregate_quantity_at_same_price_level() {
      book.place(limitBuy(100, 50));
      book.place(limitBuy(100, 30)); // same price, two orders

      var snap = book.snapshot();

      assertThat(snap.bids()).hasSize(1);
      assertThat(snap.bids().getFirst().totalQuantity()).isEqualTo(Quantity.of(80));
      assertThat(snap.bids().getFirst().orderCount()).isEqualTo(2);
    }

    @Test
    void should_produce_empty_snapshot_for_empty_book() {
      var snap = book.snapshot();
      assertThat(snap.hasBids()).isFalse();
      assertThat(snap.hasAsks()).isFalse();
    }
  }

  // ── Invariant checks ─────────────────────────────────────────────────────────

  @Nested
  class WhenInvalidInput {

    @Test
    void should_reject_order_for_wrong_symbol() {
      var wrongSymbol = Order.limitBuy(
          OrderId.generate(), Symbol.of("VALE3"), Price.of(100), Quantity.of(10),
          book.nextSequence(), Instant.now(), "k");

      assertThatThrownBy(() -> book.place(wrongSymbol))
          .isInstanceOf(InvalidOrderException.class)
          .hasMessageContaining("VALE3");
    }
  }
}
