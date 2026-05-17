package com.athena.trading.domain;

import java.util.Objects;
import java.util.UUID;

/** Globally unique identifier for an order. Immutable value object. */
public record OrderId(UUID value) {

  public OrderId {
    Objects.requireNonNull(value, "value");
  }

  public static OrderId generate() {
    return new OrderId(UUID.randomUUID());
  }

  public static OrderId of(String uuidString) {
    return new OrderId(UUID.fromString(uuidString));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
