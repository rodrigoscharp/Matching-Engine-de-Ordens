package com.athena.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class QuantityTest {

  @Test
  void should_create_quantity_with_positive_lots() {
    assertThat(Quantity.of(100).lots()).isEqualTo(100);
  }

  @Test
  void should_reject_zero_lots() {
    assertThatThrownBy(() -> Quantity.of(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void should_reject_negative_lots() {
    assertThatThrownBy(() -> Quantity.of(-5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void should_subtract_smaller_quantity() {
    var result = Quantity.of(100).subtract(Quantity.of(30));
    assertThat(result.lots()).isEqualTo(70);
  }

  @Test
  void should_reject_subtraction_exceeding_available() {
    assertThatThrownBy(() -> Quantity.of(50).subtract(Quantity.of(60)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_add_quantities() {
    assertThat(Quantity.of(40).add(Quantity.of(60)).lots()).isEqualTo(100);
  }

  @Property
  void add_and_subtract_are_inverses(
      @ForAll @LongRange(min = 1, max = 1_000_000) long a,
      @ForAll @LongRange(min = 1, max = 1_000_000) long b) {
    var base = Quantity.of(a + b);
    var delta = Quantity.of(b);
    assertThat(base.subtract(delta).lots()).isEqualTo(a);
  }

  @Property
  void add_is_commutative(
      @ForAll @LongRange(min = 1, max = 500_000) long a,
      @ForAll @LongRange(min = 1, max = 500_000) long b) {
    assertThat(Quantity.of(a).add(Quantity.of(b))).isEqualTo(Quantity.of(b).add(Quantity.of(a)));
  }
}
