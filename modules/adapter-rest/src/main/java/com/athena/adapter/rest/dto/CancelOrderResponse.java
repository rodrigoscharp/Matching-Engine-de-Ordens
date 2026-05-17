package com.athena.adapter.rest.dto;

/**
 * Response for a cancel request. Idempotent: {@code cancelled=false} means the order was already
 * gone (terminal state or not found) — not an error.
 */
public record CancelOrderResponse(String orderId, boolean cancelled) {}
