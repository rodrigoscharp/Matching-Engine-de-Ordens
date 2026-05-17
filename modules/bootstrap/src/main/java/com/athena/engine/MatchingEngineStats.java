package com.athena.engine;

/** Read-only view of matching engine runtime statistics — used by health indicators and metrics. */
public interface MatchingEngineStats {

  /** Remaining available slots in the Disruptor ring buffer. */
  long ringBufferRemainingCapacity();

  /** Total capacity of the Disruptor ring buffer (always a power of 2). */
  long ringBufferCapacity();
}
