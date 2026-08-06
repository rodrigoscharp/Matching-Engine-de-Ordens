package com.athena.trading.application.port.outbound;

import com.athena.trading.domain.OrderId;
import java.util.Optional;

/**
 * Secondary port: stores and checks idempotency keys (backed by Redis in production). Prevents
 * duplicate order submission when clients retry on network failure.
 *
 * <p><b>Why {@link #reserve} rather than a check-then-store pair</b>: two concurrent retries of the
 * same request would both pass a {@code find()} miss and both be accepted. Reservation is a single
 * atomic compare-and-set, so exactly one caller wins regardless of concurrency.
 */
public interface IdempotencyStore {

  /** Returns the previously assigned OrderId if this key was already processed. */
  Optional<OrderId> find(String idempotencyKey);

  /**
   * Atomically claims {@code idempotencyKey} for {@code orderId}. TTL is implementation-defined
   * (default: 24h).
   *
   * @return {@code true} if this caller claimed the key and should proceed; {@code false} if
   *     another submission already owns it, in which case {@link #find} returns the winner.
   */
  boolean reserve(String idempotencyKey, OrderId orderId);

  /**
   * Drops a reservation so a later retry can be processed. Called when the work guarded by the key
   * failed and must not be treated as already done.
   */
  void release(String idempotencyKey);
}
