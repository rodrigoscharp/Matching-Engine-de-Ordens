package com.athena.adapter.rest.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for the REST adapter — OpenAPI metadata. */
@Configuration
public class RestAdapterConfiguration {

  @Bean
  public OpenAPI openApiMetadata() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Athena Matching Engine API")
                .version("0.1.0-SNAPSHOT")
                .description(
                    "Price-time priority order matching engine. "
                        + "All mutating operations require an Idempotency-Key header.")
                .license(new License().name("MIT")));
  }
}
