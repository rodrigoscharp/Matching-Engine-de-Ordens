package com.athena.adapter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.athena.adapter.redis.store.RedisIdempotencyStore;
import com.athena.trading.domain.OrderId;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the Redis idempotency store. Uses a real Redis 7 via Testcontainers —
 * never an embedded or in-memory substitute.
 *
 * <p>Skipped automatically when Docker is not available.
 */
@SpringBootTest(classes = TestRedisApplication.class)
@Testcontainers(disabledWithoutDocker = true)
class RedisIdempotencyStoreIT {

  @Container
  @SuppressWarnings("resource")
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void configureRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired private RedisIdempotencyStore store;
  @Autowired private StringRedisTemplate redisTemplate;

  @BeforeEach
  void flush() {
    // Clean up between tests
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  void should_return_empty_for_unknown_key() {
    assertThat(store.find("unknown-key")).isEmpty();
  }

  @Test
  void should_store_and_retrieve_order_id() {
    var orderId = OrderId.generate();
    store.store("key-1", orderId);

    assertThat(store.find("key-1")).contains(orderId);
  }

  @Test
  void should_be_idempotent_for_same_key() {
    var first = OrderId.generate();
    var second = OrderId.generate();

    store.store("key-dup", first);
    store.store("key-dup", second); // should not overwrite

    assertThat(store.find("key-dup")).contains(first);
  }

  @Test
  void should_store_with_ttl() {
    var orderId = OrderId.generate();
    store.store("key-ttl", orderId);

    Long ttlSeconds = redisTemplate.getExpire("idempotency:key-ttl");
    assertThat(ttlSeconds).isNotNull();
    assertThat(Duration.ofSeconds(ttlSeconds)).isLessThanOrEqualTo(RedisIdempotencyStore.TTL);
    assertThat(Duration.ofSeconds(ttlSeconds)).isGreaterThan(Duration.ofHours(23));
  }

  @Test
  void should_handle_multiple_keys_independently() {
    var id1 = OrderId.generate();
    var id2 = OrderId.generate();

    store.store("key-a", id1);
    store.store("key-b", id2);

    assertThat(store.find("key-a")).contains(id1);
    assertThat(store.find("key-b")).contains(id2);
  }
}
