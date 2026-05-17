package com.athena.adapter.persistence.adapter;

import com.athena.adapter.persistence.mapper.OrderEventMapper;
import com.athena.adapter.persistence.repository.SpringDataOrderEventRepository;
import com.athena.trading.application.port.outbound.OrderEventStore;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.event.OrderEvent;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence adapter: implements the {@link OrderEventStore} outbound port using Spring Data JDBC
 * + PostgreSQL. Append-only — no updates or deletes.
 *
 * <p>The {@code @Transactional} annotation is on the adapter (infrastructure layer), NOT on the
 * domain or application service — keeping transaction management at the infrastructure boundary.
 */
@Repository
public class OrderEventStoreAdapter implements OrderEventStore {

  private final SpringDataOrderEventRepository repository;
  private final OrderEventMapper mapper;

  public OrderEventStoreAdapter(
      SpringDataOrderEventRepository repository, OrderEventMapper mapper) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  @Transactional
  public void append(List<OrderEvent> events) {
    var records = events.stream().map(mapper::toRecord).toList();
    repository.saveAll(records);
  }

  @Override
  public List<OrderEvent> loadEvents(OrderId orderId) {
    return repository
        .findByOrderIdOrderByIdAsc(orderId.value().toString())
        .stream()
        .map(mapper::toDomain)
        .toList();
  }
}
