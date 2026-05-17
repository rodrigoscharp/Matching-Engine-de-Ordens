package com.athena.gatling;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.UUID;

/**
 * Gatling load simulation for the Athena REST API.
 *
 * <p>Run: {@code make load} (requires a running instance on localhost:8080)
 *
 * <h3>Scenarios</h3>
 * <ul>
 *   <li><b>Smoke</b>: 1 user — sanity check before running load
 *   <li><b>Steady state</b>: 50 concurrent users submitting limit orders for 60s
 *   <li><b>Ramp</b>: 0 → 200 users over 30s, then hold for 60s — finds the saturation point
 * </ul>
 *
 * <h3>Performance targets (Sprint 5)</h3>
 * <ul>
 *   <li>p99 response time &lt; 5ms at 1 000 req/s
 *   <li>Error rate &lt; 0.1%
 *   <li>Throughput &gt; 5 000 req/s at peak
 * </ul>
 */
public class OrderSimulation extends Simulation {

  private static final String BASE_URL =
      System.getProperty("gatling.baseUrl", "http://localhost:8080");

  private static final HttpProtocolBuilder HTTP =
      http.baseUrl(BASE_URL)
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .shareConnections();

  // ── Actions ────────────────────────────────────────────────────────────────────

  private final ActionBuilder placeLimitBuy =
      http("Place limit buy")
          .post("/api/v1/orders")
          .header("Idempotency-Key", session -> UUID.randomUUID().toString())
          .body(
              StringBody(
                  """
                  {"symbol":"PETR4","side":"BUY","type":"LIMIT","price":"10.50","quantity":"100"}
                  """))
          .check(status().is(201));

  private final ActionBuilder placeLimitSell =
      http("Place limit sell")
          .post("/api/v1/orders")
          .header("Idempotency-Key", session -> UUID.randomUUID().toString())
          .body(
              StringBody(
                  """
                  {"symbol":"PETR4","side":"SELL","type":"LIMIT","price":"10.50","quantity":"100"}
                  """))
          .check(status().is(201));

  private final ActionBuilder getBookSnapshot =
      http("Get book snapshot")
          .get("/api/v1/books/PETR4")
          .check(status().is(200));

  private final ActionBuilder healthCheck =
      http("Health check")
          .get("/actuator/health")
          .check(status().is(200));

  // ── Scenarios ─────────────────────────────────────────────────────────────────

  private final ScenarioBuilder smoke =
      scenario("Smoke")
          .exec(healthCheck)
          .exec(placeLimitBuy)
          .exec(placeLimitSell)
          .exec(getBookSnapshot);

  private final ScenarioBuilder orderSubmission =
      scenario("Order submission — steady state")
          .exec(placeLimitBuy)
          .exec(placeLimitSell);

  private final ScenarioBuilder mixedWorkload =
      scenario("Mixed workload")
          .exec(placeLimitBuy)
          .exec(getBookSnapshot)
          .exec(placeLimitSell)
          .exec(getBookSnapshot);

  // ── Simulation setup ──────────────────────────────────────────────────────────

  {
    setUp(
            smoke.injectOpen(atOnceUsers(1)),
            orderSubmission.injectOpen(
                constantUsersPerSec(50).during(Duration.ofSeconds(60))),
            mixedWorkload.injectOpen(
                rampUsersPerSec(0).to(200).during(Duration.ofSeconds(30)),
                constantUsersPerSec(200).during(Duration.ofSeconds(60))))
        .protocols(HTTP)
        .assertions(
            global().responseTime().percentile(99).lt(5_000),
            global().successfulRequests().percent().gt(99.9));
  }
}
