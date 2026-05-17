package com.athena.adapter.redis.store;

import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.domain.OrderId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed idempotency store with a 24-hour TTL.
 *
 * <p>Keys are stored as {@code idempotency:{key}} → {@code orderId} (UUID string). Uses
 * {@code SETNX} semantics ({@link StringRedisTemplate#opsForValue()#setIfAbsent}) so concurrent
 * retries from the same client key are safe.
 *
 * <p>Replaces {@code InMemoryIdempotencyStore} from the bootstrap module — that bean is
 * conditional on this one not being present.
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

  static final Duration TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "idempotency:";

  private final StringRedisTemplate redis;

  public RedisIdempotencyStore(StringRedisTemplate redis) {
    this.redis = Objects.requireNonNull(redis, "redis");
  }

  @Override
  public Optional<OrderId> find(String idempotencyKey) {
    String value = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
    return Optional.ofNullable(value).map(OrderId::of);
  }

  @Override
  public void store(String idempotencyKey, OrderId orderId) {
    redis.opsForValue()
        .setIfAbsent(KEY_PREFIX + idempotencyKey, orderId.value().toString(), TTL);
  }
}
