package com.athena.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.athena.adapter.persistence.adapter.OrderEventStoreAdapter;
import com.athena.adapter.persistence.mapper.OrderEventMapper;
import com.athena.adapter.persistence.repository.SpringDataOrderEventRepository;
import com.athena.trading.domain.OrderId;
import com.athena.trading.domain.OrderSide;
import com.athena.trading.domain.OrderType;
import com.athena.trading.domain.Price;
import com.athena.trading.domain.Quantity;
import com.athena.trading.domain.Symbol;
import com.athena.trading.domain.TradeId;
import com.athena.trading.domain.event.OrderCancelled;
import com.athena.trading.domain.event.OrderEvent;
import com.athena.trading.domain.event.OrderPlaced;
import com.athena.trading.domain.event.TradeExecuted;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the persistence adapter. Uses a real PostgreSQL 16 via Testcontainers —
 * never H2 or any embedded database.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OrderEventStoreAdapter.class, OrderEventMapper.class, OrderEventStoreAdapterIT.TestConfig.class})
@Sql("/db/migration/V1__create_order_events.sql")
@Testcontainers(disabledWithoutDocker = true)
class OrderEventStoreAdapterIT {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("athena_test");

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private OrderEventStoreAdapter adapter;
  @Autowired private SpringDataOrderEventRepository repository;

  private static final Symbol PETR4 = Symbol.of("PETR4");
  private static final Instant NOW = Instant.now();

  @Test
  void should_append_order_placed_event_and_load_it_back() {
    var orderId = OrderId.generate();
    var event = orderPlacedEvent(orderId);

    adapter.append(List.of(event));

    var loaded = adapter.loadEvents(orderId);
    assertThat(loaded).hasSize(1);
    assertThat(loaded.getFirst()).isInstanceOf(OrderPlaced.class);
    var loaded0 = (OrderPlaced) loaded.getFirst();
    assertThat(loaded0.orderId()).isEqualTo(orderId);
    assertThat(loaded0.side()).isEqualTo(OrderSide.BUY);
    assertThat(loaded0.limitPrice()).contains(Price.of(1050));
  }

  @Test
  void should_append_multiple_events_for_same_order_in_insertion_order() {
    var buyId = OrderId.generate();
    var sellId = OrderId.generate();

    var placed = orderPlacedEvent(buyId);
    var traded =
        new TradeExecuted(
            TradeId.generate(),
            PETR4,
            buyId,
            sellId,
            Price.of(1050),
            Quantity.of(50),
            NOW);

    adapter.append(List.of(placed, traded));

    var loaded = adapter.loadEvents(buyId);
    assertThat(loaded).hasSize(2);
    assertThat(loaded.get(0)).isInstanceOf(OrderPlaced.class);
    assertThat(loaded.get(1)).isInstanceOf(TradeExecuted.class);
  }

  @Test
  void should_append_order_cancelled_event() {
    var orderId = OrderId.generate();
    var cancelled = new OrderCancelled(orderId, PETR4, Quantity.of(100), NOW);

    adapter.append(List.of(cancelled));

    var loaded = adapter.loadEvents(orderId);
    assertThat(loaded).hasSize(1);
    assertThat(loaded.getFirst()).isInstanceOf(OrderCancelled.class);
    var c = (OrderCancelled) loaded.getFirst();
    assertThat(c.cancelledQuantity()).isEqualTo(Quantity.of(100));
  }

  @Test
  void should_return_empty_list_for_unknown_order_id() {
    var unknown = OrderId.generate();
    assertThat(adapter.loadEvents(unknown)).isEmpty();
  }

  @Test
  void should_persist_market_order_event_without_price() {
    var orderId = OrderId.generate();
    var event =
        new OrderPlaced(
            orderId, PETR4, OrderSide.BUY, OrderType.MARKET,
            Optional.empty(), Quantity.of(100), 1L, NOW, "key-mkt", NOW);

    adapter.append(List.of(event));

    var loaded = (OrderPlaced) adapter.loadEvents(orderId).getFirst();
    assertThat(loaded.type()).isEqualTo(OrderType.MARKET);
    assertThat(loaded.limitPrice()).isEmpty();
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  private static OrderPlaced orderPlacedEvent(OrderId orderId) {
    return new OrderPlaced(
        orderId, PETR4, OrderSide.BUY, OrderType.LIMIT,
        Optional.of(Price.of(1050)), Quantity.of(100), 1L, NOW, "key-" + orderId, NOW);
  }

  @Configuration
  static class TestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().registerModule(new JavaTimeModule());
    }
  }
}
