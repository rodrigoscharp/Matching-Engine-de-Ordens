package com.athena.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Minimal entry point for persistence slice tests.
 *
 * <p>Without a {@code @SpringBootConfiguration} on the classpath, {@code @DataJdbcTest} cannot
 * determine its auto-configuration base packages. Note that a nested {@code @Configuration} class
 * inside a test does NOT satisfy this: Spring Boot treats it as the context configuration and
 * stops looking, which is how these tests came to fail the moment Docker was available.
 */
@SpringBootApplication
public class TestPersistenceApplication {

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }
}
