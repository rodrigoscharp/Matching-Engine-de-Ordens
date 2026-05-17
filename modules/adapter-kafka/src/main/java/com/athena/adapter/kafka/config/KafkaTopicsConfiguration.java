package com.athena.adapter.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares Kafka topics. Spring Boot's Kafka auto-configuration creates them on startup if they
 * don't exist (requires a running broker or will silently skip in tests with embedded Kafka).
 */
@Configuration
public class KafkaTopicsConfiguration {

  @Value("${athena.kafka.topics.partitions:3}")
  private int partitions;

  @Value("${athena.kafka.topics.replication-factor:1}")
  private short replicationFactor;

  @Bean
  public NewTopic orderPlacedTopic() {
    return TopicBuilder.name(KafkaTopics.ORDER_PLACED)
        .partitions(partitions)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic tradeExecutedTopic() {
    return TopicBuilder.name(KafkaTopics.TRADE_EXECUTED)
        .partitions(partitions)
        .replicas(replicationFactor)
        .build();
  }

  @Bean
  public NewTopic orderCancelledTopic() {
    return TopicBuilder.name(KafkaTopics.ORDER_CANCELLED)
        .partitions(partitions)
        .replicas(replicationFactor)
        .build();
  }
}
