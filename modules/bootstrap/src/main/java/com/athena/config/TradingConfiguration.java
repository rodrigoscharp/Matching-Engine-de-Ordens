package com.athena.config;

import com.athena.infra.InMemoryIdempotencyStore;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.application.service.TradingApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application layer with its outbound ports and exposes the inbound use-case ports.
 *
 * <p><b>Sprint 5:</b> {@link com.athena.engine.DisruptorMatchingEngine} is a {@code @Component}
 * that implements all three inbound ports ({@link PlaceOrderUseCase}, {@link CancelOrderUseCase},
 * {@link GetBookSnapshotUseCase}). Because it is registered by component-scan before these
 * {@code @ConditionalOnMissingBean} fallbacks are evaluated, the Disruptor engine is always the
 * active implementation. The {@link TradingApplicationService} bean is kept for direct unit-test
 * access but is not bound to the inbound ports.
 *
 * <p>Fallback beans for {@link DomainEventPublisher} and {@link IdempotencyStore} activate only in
 * slice tests where the Kafka/Redis adapters are not on the test classpath.
 */
@Configuration
public class TradingConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TradingConfiguration.class);

  /**
   * Kept as a named bean so that it can be injected into tests directly (e.g., to pre-populate
   * order books). Not bound to any inbound port in production — the Disruptor engine handles that.
   */
  @Bean
  public TradingApplicationService tradingApplicationService(
      OrderEventStore orderEventStore,
      DomainEventPublisher domainEventPublisher,
      IdempotencyStore idempotencyStore) {
    return new TradingApplicationService(orderEventStore, domainEventPublisher, idempotencyStore);
  }

  // ── Inbound port fallbacks (inactive when DisruptorMatchingEngine is present) ──

  @Bean
  @ConditionalOnMissingBean(PlaceOrderUseCase.class)
  public PlaceOrderUseCase placeOrderUseCase(TradingApplicationService service) {
    log.warn("DisruptorMatchingEngine not found — falling back to synchronous TradingApplicationService");
    return service;
  }

  @Bean
  @ConditionalOnMissingBean(CancelOrderUseCase.class)
  public CancelOrderUseCase cancelOrderUseCase(TradingApplicationService service) {
    return service;
  }

  @Bean
  @ConditionalOnMissingBean(GetBookSnapshotUseCase.class)
  public GetBookSnapshotUseCase getBookSnapshotUseCase(TradingApplicationService service) {
    return service;
  }

  // ── Outbound port fallbacks (inactive when real adapters are present) ──────────

  @Bean
  @ConditionalOnMissingBean(IdempotencyStore.class)
  public IdempotencyStore fallbackIdempotencyStore() {
    log.warn("No IdempotencyStore adapter — using in-memory fallback (NOT production-safe)");
    return new InMemoryIdempotencyStore();
  }

  @Bean
  @ConditionalOnMissingBean(DomainEventPublisher.class)
  public DomainEventPublisher fallbackDomainEventPublisher() {
    log.warn("No DomainEventPublisher adapter — using no-op fallback (events not published to Kafka)");
    return events -> {};
  }
}
