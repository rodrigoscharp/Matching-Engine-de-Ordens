package com.athena.infra;

import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.domain.OrderId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory idempotency store. Used in Sprint 3 until the Redis adapter is implemented in Sprint
 * 4. NOT persistent — keys are lost on restart.
 *
 * <p>TODO(Sprint 4): replace with Redis-backed implementation in adapter-redis.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

  private final ConcurrentHashMap<String, OrderId> store = new ConcurrentHashMap<>();

  @Override
  public Optional<OrderId> find(String idempotencyKey) {
    return Optional.ofNullable(store.get(idempotencyKey));
  }

  @Override
  public void store(String idempotencyKey, OrderId orderId) {
    store.putIfAbsent(idempotencyKey, orderId);
  }
}
