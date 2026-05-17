package com.athena.adapter.redis.config;

import org.springframework.context.annotation.Configuration;

/**
 * Redis adapter configuration. Spring Boot auto-configures {@link
 * org.springframework.data.redis.core.StringRedisTemplate} when {@code
 * spring-boot-starter-data-redis} is on the classpath; no explicit bean definition is needed for
 * basic String operations.
 *
 * <p>Additional configuration (custom serializers, connection pool tuning, Sentinel/Cluster mode)
 * should be added here as needed.
 */
@Configuration
public class RedisConfiguration {
  // Spring Boot auto-configures StringRedisTemplate via RedisAutoConfiguration
}
