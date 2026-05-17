package com.athena.trading.domain;

/** Thrown when an operation references an order that does not exist in the book. */
public final class OrderNotFoundException extends RuntimeException {

  public OrderNotFoundException(OrderId orderId) {
    super("Order not found: " + orderId);
  }
}
