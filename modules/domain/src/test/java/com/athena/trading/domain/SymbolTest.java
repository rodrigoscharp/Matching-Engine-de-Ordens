package com.athena.trading.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SymbolTest {

  @Test
  void should_normalize_to_uppercase() {
    assertThat(Symbol.of("petr4").value()).isEqualTo("PETR4");
    assertThat(Symbol.of("BTC-USD").value()).isEqualTo("BTC-USD");
  }

  @Test
  void should_reject_blank_symbol() {
    assertThatThrownBy(() -> Symbol.of(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
    assertThatThrownBy(() -> Symbol.of("   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_reject_null_symbol() {
    assertThatThrownBy(() -> Symbol.of(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void should_be_equal_regardless_of_input_case() {
    assertThat(Symbol.of("petr4")).isEqualTo(Symbol.of("PETR4"));
  }

  @Test
  void should_have_consistent_toString() {
    assertThat(Symbol.of("petr4").toString()).isEqualTo("PETR4");
  }
}
