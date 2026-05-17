package com.athena.adapter.rest.handler;

import com.athena.adapter.rest.dto.ApiErrorResponse;
import com.athena.trading.domain.InvalidOrderException;
import com.athena.trading.domain.OrderNotFoundException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralizes exception-to-HTTP-response mapping. Business exceptions become 4xx; unexpected
 * errors become 500 with no internal detail leaked to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    List<String> details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .toList();
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of(400, "Validation Failed", "Request validation failed", details));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of(400, "Missing Header", "Required header: " + ex.getHeaderName()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of(400, "Bad Request", "Malformed or unreadable request body"));
  }

  @ExceptionHandler(InvalidOrderException.class)
  public ResponseEntity<ApiErrorResponse> handleInvalidOrder(InvalidOrderException ex) {
    return ResponseEntity.unprocessableEntity()
        .body(ApiErrorResponse.of(422, "Invalid Order", ex.getMessage()));
  }

  @ExceptionHandler(OrderNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(OrderNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorResponse.of(404, "Not Found", ex.getMessage()));
  }

  @ExceptionHandler(ArithmeticException.class)
  public ResponseEntity<ApiErrorResponse> handleArithmetic(ArithmeticException ex) {
    return ResponseEntity.badRequest()
        .body(
            ApiErrorResponse.of(
                400,
                "Bad Request",
                "Price precision exceeds maximum of 2 decimal places. Use a value like 10.50, not 10.505"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
    return ResponseEntity.internalServerError()
        .body(ApiErrorResponse.of(500, "Internal Server Error", "An unexpected error occurred"));
  }
}
