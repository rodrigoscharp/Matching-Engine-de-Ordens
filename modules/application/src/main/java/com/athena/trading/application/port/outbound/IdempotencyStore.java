package com.athena.trading.application.port.outbound;

import com.athena.trading.domain.OrderId;
import java.util.Optional;

/**
 * Secondary port: stores and checks idempotency keys (backed by Redis in production). Prevents
 * duplicate order submission when clients retry on network failure.
 */
public interface IdempotencyStore {

  /** Returns the previously assigned OrderId if this key was already processed. */
  Optional<OrderId> find(String idempotencyKey);

  /** Associates a key with an OrderId. TTL is implementation-defined (default: 24h). */
  void store(String idempotencyKey, OrderId orderId);
}
