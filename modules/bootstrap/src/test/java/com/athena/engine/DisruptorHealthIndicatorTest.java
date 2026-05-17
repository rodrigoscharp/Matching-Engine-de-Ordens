package com.athena.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;

@ExtendWith(MockitoExtension.class)
class DisruptorHealthIndicatorTest {

  // Interface — Mockito can always mock interfaces, even on JDK 25
  @Mock private MatchingEngineStats engine;
  @InjectMocks private DisruptorHealthIndicator indicator;

  @Test
  void should_report_up_when_buffer_is_mostly_empty() {
    when(engine.ringBufferCapacity()).thenReturn(4096L);
    when(engine.ringBufferRemainingCapacity()).thenReturn(4000L); // ~2.4% used

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsKey("bufferUtilization");
    assertThat(health.getDetails().get("bufferUtilization").toString()).contains("2.3%");
  }

  @Test
  void should_report_up_when_buffer_is_at_89_percent() {
    when(engine.ringBufferCapacity()).thenReturn(4096L);
    when(engine.ringBufferRemainingCapacity()).thenReturn(450L); // ~89% used

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void should_report_down_when_buffer_exceeds_90_percent() {
    when(engine.ringBufferCapacity()).thenReturn(4096L);
    when(engine.ringBufferRemainingCapacity()).thenReturn(100L); // >90% used

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("status");
    assertThat(health.getDetails().get("status").toString()).contains("backpressure");
  }

  @Test
  void should_report_up_when_buffer_is_exactly_at_zero_capacity_edge() {
    when(engine.ringBufferCapacity()).thenReturn(0L);
    when(engine.ringBufferRemainingCapacity()).thenReturn(0L);

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void should_expose_buffer_details_in_health_response() {
    when(engine.ringBufferCapacity()).thenReturn(4096L);
    when(engine.ringBufferRemainingCapacity()).thenReturn(3072L); // 25% used

    var health = indicator.health();

    assertThat(health.getDetails())
        .containsKeys("bufferUtilization", "remainingCapacity", "totalCapacity");
    assertThat(health.getDetails().get("remainingCapacity")).isEqualTo(3072L);
    assertThat(health.getDetails().get("totalCapacity")).isEqualTo(4096L);
  }
}
