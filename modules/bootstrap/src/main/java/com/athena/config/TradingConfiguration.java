package com.athena.config;

import com.athena.infra.InMemoryIdempotencyStore;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.application.service.TradingApplicationService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application service with its inbound and outbound ports.
 *
 * <p>The application service lives in the framework-free application module and is never a Spring
 * component — this class is the sole point of integration.
 *
 * <p>Fallback beans for {@link DomainEventPublisher} and {@link IdempotencyStore} activate only
 * when no real adapter implementation is on the classpath (e.g., in slice tests that don't load
 * the Kafka or Redis adapters). In production, {@code KafkaDomainEventPublisher} and
 * {@code RedisIdempotencyStore} from their respective adapter modules take precedence.
 */
@Configuration
public class TradingConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TradingConfiguration.class);

  @Bean
  public TradingApplicationService tradingApplicationService(
      OrderEventStore orderEventStore,
      DomainEventPublisher domainEventPublisher,
      IdempotencyStore idempotencyStore) {
    return new TradingApplicationService(orderEventStore, domainEventPublisher, idempotencyStore);
  }

  @Bean
  public PlaceOrderUseCase placeOrderUseCase(TradingApplicationService service) {
    return service;
  }

  @Bean
  public CancelOrderUseCase cancelOrderUseCase(TradingApplicationService service) {
    return service;
  }

  @Bean
  public GetBookSnapshotUseCase getBookSnapshotUseCase(TradingApplicationService service) {
    return service;
  }

  // ── Fallback beans (Sprint 3 stubs — inactive when real adapters are present) ──

  /**
   * In-memory fallback used when no {@link IdempotencyStore} adapter is on the classpath.
   * NOT persistent: keys are lost on restart. Replace with Redis for production.
   *
   * <p>TODO(Sprint 4 done): RedisIdempotencyStore in adapter-redis is the real implementation.
   */
  @Bean
  @ConditionalOnMissingBean(IdempotencyStore.class)
  public IdempotencyStore fallbackIdempotencyStore() {
    log.warn("No IdempotencyStore adapter found — using in-memory fallback (NOT production-safe)");
    return new InMemoryIdempotencyStore();
  }

  /**
   * No-op fallback used when no {@link DomainEventPublisher} adapter is on the classpath.
   * Events are persisted in the event store but not published to Kafka.
   *
   * <p>TODO(Sprint 4 done): KafkaDomainEventPublisher in adapter-kafka is the real implementation.
   */
  @Bean
  @ConditionalOnMissingBean(DomainEventPublisher.class)
  public DomainEventPublisher fallbackDomainEventPublisher() {
    log.warn("No DomainEventPublisher adapter found — using no-op fallback (events not published to Kafka)");
    return events -> {};
  }
}
