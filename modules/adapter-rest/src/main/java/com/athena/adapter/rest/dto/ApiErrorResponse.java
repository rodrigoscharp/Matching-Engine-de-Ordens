package com.athena.adapter.rest.dto;

import java.time.Instant;
import java.util.List;

/** Uniform error body for all 4xx/5xx responses. */
public record ApiErrorResponse(
    int status, String error, String message, List<String> details, Instant timestamp) {

  public static ApiErrorResponse of(int status, String error, String message) {
    return new ApiErrorResponse(status, error, message, List.of(), Instant.now());
  }

  public static ApiErrorResponse of(
      int status, String error, String message, List<String> details) {
    return new ApiErrorResponse(status, error, message, List.copyOf(details), Instant.now());
  }
}
