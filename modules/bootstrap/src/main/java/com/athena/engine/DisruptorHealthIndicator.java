package com.athena.engine;

import java.util.Locale;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator for the Disruptor ring buffer.
 *
 * <p>Reports {@code DOWN} when the ring buffer exceeds 90% utilization — the system is under
 * extreme backpressure and new orders risk timeouts. A Prometheus alert fires at 80% (see
 * {@code ops/prometheus/alerts.yml}).
 *
 * <p>Visible at {@code /actuator/health/disruptor}.
 */
@Component("disruptor")
public class DisruptorHealthIndicator implements HealthIndicator {

  private static final double CRITICAL_THRESHOLD = 0.9;

  private final MatchingEngineStats engine;

  public DisruptorHealthIndicator(MatchingEngineStats engine) {
    this.engine = engine;
  }

  @Override
  public Health health() {
    long remaining = engine.ringBufferRemainingCapacity();
    long total = engine.ringBufferCapacity();
    double utilization = total > 0 ? (double) (total - remaining) / total : 0.0;
    String utilizationPct = String.format(Locale.ROOT, "%.1f%%", utilization * 100);

    if (utilization >= CRITICAL_THRESHOLD) {
      return Health.down()
          .withDetail("status", "Ring buffer critical — backpressure required")
          .withDetail("bufferUtilization", utilizationPct)
          .withDetail("remainingCapacity", remaining)
          .withDetail("totalCapacity", total)
          .build();
    }

    return Health.up()
        .withDetail("bufferUtilization", utilizationPct)
        .withDetail("remainingCapacity", remaining)
        .withDetail("totalCapacity", total)
        .build();
  }
}
