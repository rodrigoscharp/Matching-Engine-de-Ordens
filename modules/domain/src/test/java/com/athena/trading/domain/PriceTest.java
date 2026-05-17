package com.athena.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class PriceTest {

  @Test
  void should_create_price_with_positive_ticks() {
    var price = Price.of(100);
    assertThat(price.ticks()).isEqualTo(100);
  }

  @Test
  void should_reject_zero_ticks() {
    assertThatThrownBy(() -> Price.of(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void should_reject_negative_ticks() {
    assertThatThrownBy(() -> Price.of(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void should_cross_buy_when_bid_price_meets_or_exceeds_ask() {
    var bid = Price.of(100);
    var ask = Price.of(99);
    assertThat(bid.crossesBuy(ask)).isTrue();
    assertThat(bid.crossesBuy(Price.of(100))).isTrue(); // equal price crosses
    assertThat(bid.crossesBuy(Price.of(101))).isFalse();
  }

  @Test
  void should_cross_sell_when_ask_price_meets_or_undercuts_bid() {
    var ask = Price.of(99);
    var bid = Price.of(100);
    assertThat(ask.crossesSell(bid)).isTrue();
    assertThat(Price.of(100).crossesSell(Price.of(100))).isTrue(); // equal price crosses
    assertThat(Price.of(101).crossesSell(Price.of(100))).isFalse();
  }

  @Test
  void should_compare_prices_by_ticks() {
    assertThat(Price.of(50)).isLessThan(Price.of(100));
    assertThat(Price.of(100)).isGreaterThan(Price.of(50));
    assertThat(Price.of(100)).isEqualByComparingTo(Price.of(100));
  }

  @Test
  void should_be_value_equal_on_same_ticks() {
    assertThat(Price.of(100)).isEqualTo(Price.of(100));
    assertThat(Price.of(100).hashCode()).isEqualTo(Price.of(100).hashCode());
  }

  @Property
  void cross_buy_is_transitive(
      @ForAll @LongRange(min = 1, max = 1_000_000) long a,
      @ForAll @LongRange(min = 1, max = 1_000_000) long b,
      @ForAll @LongRange(min = 1, max = 1_000_000) long c) {
    Price pa = Price.of(a), pb = Price.of(b), pc = Price.of(c);
    if (pa.crossesBuy(pb) && pb.crossesBuy(pc)) {
      assertThat(pa.crossesBuy(pc)).isTrue();
    }
  }
}
