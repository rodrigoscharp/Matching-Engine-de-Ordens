package com.athena.infra;

import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.domain.OrderId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency store. Used as a fallback when no Redis adapter is on the classpath (e.g.
 * in slice tests). NOT persistent — keys are lost on restart.
 *
 * <p>In production, {@code RedisIdempotencyStore} in {@code adapter-redis} is the active bean;
 * this class is only instantiated via {@code @ConditionalOnMissingBean} in TradingConfiguration.
 */
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
