package com.athena.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final Symbol PETR4 = Symbol.of("PETR4");
  private static final Instant NOW = Instant.now();

  @Test
  void should_create_limit_buy_with_open_status() {
    var order = limitBuy(Price.of(100), Quantity.of(200));

    assertThat(order.side()).isEqualTo(OrderSide.BUY);
    assertThat(order.type()).isEqualTo(OrderType.LIMIT);
    assertThat(order.status()).isEqualTo(OrderStatus.OPEN);
    assertThat(order.remainingQuantity()).isEqualTo(Quantity.of(200));
    assertThat(order.limitPrice()).contains(Price.of(100));
    assertThat(order.isOpen()).isTrue();
    assertThat(order.isFilled()).isFalse();
  }

  @Test
  void should_create_market_buy_without_limit_price() {
    var order = Order.marketBuy(OrderId.generate(), PETR4, Quantity.of(50), 1, NOW, "key-1");

    assertThat(order.type()).isEqualTo(OrderType.MARKET);
    assertThat(order.limitPrice()).isEmpty();
    assertThat(order.isOpen()).isTrue();
  }

  @Test
  void should_reject_limit_order_without_price() {
    assertThatThrownBy(
            () -> Order.limitBuy(OrderId.generate(), PETR4, null, Quantity.of(10), 1, NOW, "k"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("price");
  }

  @Test
  void should_transition_to_partially_filled_when_partially_matched() {
    var order = limitBuy(Price.of(100), Quantity.of(100));

    order.fill(60); // package-private, same package

    assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.remainingQuantity()).isEqualTo(Quantity.of(40));
    assertThat(order.isOpen()).isTrue();
  }

  @Test
  void should_transition_to_filled_when_fully_matched() {
    var order = limitBuy(Price.of(100), Quantity.of(100));

    order.fill(100);

    assertThat(order.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(order.isFilled()).isTrue();
    assertThat(order.isOpen()).isFalse();
    // remainingQuantity() is not called on a filled order — remaining lots = 0 is not a valid
    // Quantity (Quantity enforces lots > 0 by design); use isFilled() to test terminal state
  }

  @Test
  void should_not_allow_overfill() {
    var order = limitBuy(Price.of(100), Quantity.of(50));

    assertThatThrownBy(() -> order.fill(60))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot fill");
  }

  @Test
  void should_not_allow_zero_fill() {
    var order = limitBuy(Price.of(100), Quantity.of(50));

    assertThatThrownBy(() -> order.fill(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_transition_to_cancelled_when_cancelled() {
    var order = limitBuy(Price.of(100), Quantity.of(50));

    order.cancel();

    assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.isCancelled()).isTrue();
    assertThat(order.isOpen()).isFalse();
  }

  @Test
  void should_not_allow_cancelling_filled_order() {
    var order = limitBuy(Price.of(100), Quantity.of(50));
    order.fill(50);

    assertThatThrownBy(order::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");
  }

  @Test
  void should_use_order_id_for_equality() {
    var id = OrderId.generate();
    var o1 = Order.limitBuy(id, PETR4, Price.of(100), Quantity.of(10), 1, NOW, "k1");
    var o2 = Order.limitBuy(id, PETR4, Price.of(200), Quantity.of(20), 2, NOW, "k2");

    assertThat(o1).isEqualTo(o2);
    assertThat(o1.hashCode()).isEqualTo(o2.hashCode());
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  private static Order limitBuy(Price price, Quantity qty) {
    return Order.limitBuy(OrderId.generate(), PETR4, price, qty, 1, NOW, "idemp-" + System.nanoTime());
  }
}
