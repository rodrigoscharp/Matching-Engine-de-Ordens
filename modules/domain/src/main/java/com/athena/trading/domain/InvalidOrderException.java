package com.athena.trading.domain;

/** Thrown when an order violates domain invariants (bad price, zero quantity, wrong symbol…). */
public final class InvalidOrderException extends RuntimeException {

  public InvalidOrderException(String message) {
    super(message);
  }

  public InvalidOrderException(String message, Throwable cause) {
    super(message, cause);
  }
}
