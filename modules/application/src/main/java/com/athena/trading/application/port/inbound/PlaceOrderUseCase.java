package com.athena.trading.application.port.inbound;

import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.domain.OrderId;

/** Primary port: submit a new order to the matching engine. */
public interface PlaceOrderUseCase {

  /**
   * Places an order. Idempotent — repeating the same {@code idempotencyKey} returns the original
   * {@code OrderId} without re-processing.
   *
   * @return the assigned order identifier
   */
  OrderId place(PlaceOrderCommand command);
}
