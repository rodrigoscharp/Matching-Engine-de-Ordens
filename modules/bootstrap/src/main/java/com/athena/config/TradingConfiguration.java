package com.athena.config;

import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.application.port.outbound.IdempotencyStore;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.application.service.TradingApplicationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application service with its inbound and outbound ports. The service is not a Spring
 * component — it lives in the framework-free application module. This configuration class is the
 * adapter that connects it to the Spring context.
 */
@Configuration
public class TradingConfiguration {

  @Bean
  public TradingApplicationService tradingApplicationService(
      OrderEventStore orderEventStore,
      DomainEventPublisher domainEventPublisher,
      IdempotencyStore idempotencyStore) {
    return new TradingApplicationService(orderEventStore, domainEventPublisher, idempotencyStore);
  }

  /** Exposes the service as the PlaceOrderUseCase port. */
  @Bean
  public PlaceOrderUseCase placeOrderUseCase(TradingApplicationService service) {
    return service;
  }

  /** Exposes the service as the CancelOrderUseCase port. */
  @Bean
  public CancelOrderUseCase cancelOrderUseCase(TradingApplicationService service) {
    return service;
  }

  /** Exposes the service as the GetBookSnapshotUseCase port. */
  @Bean
  public GetBookSnapshotUseCase getBookSnapshotUseCase(TradingApplicationService service) {
    return service;
  }
}
