package com.athena.infra;

import com.athena.trading.application.port.outbound.DomainEventPublisher;
import com.athena.trading.domain.event.OrderEvent;
import java.util.List;

/**
 * No-op event publisher. Kept as an internal class for documentation/testing purposes. In
 * production, {@code KafkaDomainEventPublisher} in {@code adapter-kafka} is the active bean;
 * this class is referenced only in TradingConfiguration as a @ConditionalOnMissingBean fallback.
 */
public class NoOpDomainEventPublisher implements DomainEventPublisher {

  @Override
  public void publish(List<OrderEvent> events) {
    
  }
}
