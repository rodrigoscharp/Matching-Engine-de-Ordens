package com.athena.trading.application.exception;

/**
 * The matching engine did not produce a result within its deadline.
 *
 * <p>Distinct from a domain rejection: nothing about the request was wrong, so the caller may
 * retry the same {@code Idempotency-Key}. Adapters map this to a 503, not a 500 — the difference
 * tells a client whether retrying is pointless or exactly the right move.
 */
public class MatchingEngineUnavailableException extends RuntimeException {

  public MatchingEngineUnavailableException(String message) {
    super(message);
  }

  public MatchingEngineUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
