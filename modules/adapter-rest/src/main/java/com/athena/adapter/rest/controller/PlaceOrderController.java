package com.athena.adapter.rest.controller;

import com.athena.adapter.rest.dto.CancelOrderResponse;
import com.athena.adapter.rest.dto.PlaceOrderRequest;
import com.athena.adapter.rest.dto.PlaceOrderResponse;
import com.athena.trading.application.command.CancelOrderCommand;
import com.athena.trading.application.command.PlaceOrderCommand;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST adapter for order lifecycle: place and cancel.
 *
 * <p>All mutating operations require an {@code Idempotency-Key} header. Repeating a request with
 * the same key is safe — the original result is returned without re-processing.
 *
 * <p>Prices are accepted as BigDecimal and converted to ticks internally (ADR-006). The current
 * multiplier is 100 (2 decimal places, e.g., BRL cents). Instrument-specific tick sizes are not
 * yet implemented.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Orders", description = "Order lifecycle — place and cancel")
public class PlaceOrderController {

  // 1 BRL = 100 ticks (2 decimal places). Instrument-specific config not yet implemented.
  static final long TICK_MULTIPLIER = 100;

  private final PlaceOrderUseCase placeOrderUseCase;
  private final CancelOrderUseCase cancelOrderUseCase;

  public PlaceOrderController(
      PlaceOrderUseCase placeOrderUseCase, CancelOrderUseCase cancelOrderUseCase) {
    this.placeOrderUseCase = placeOrderUseCase;
    this.cancelOrderUseCase = cancelOrderUseCase;
  }

  @PostMapping("/orders")
  @Operation(
      summary = "Place a new order",
      responses = {
        @ApiResponse(responseCode = "201", description = "Order accepted"),
        @ApiResponse(responseCode = "400", description = "Validation error or missing Idempotency-Key"),
        @ApiResponse(responseCode = "422", description = "Domain rule violation")
      })
  public ResponseEntity<PlaceOrderResponse> placeOrder(
      @Parameter(description = "Client-generated UUID; repeating the same key is idempotent")
          @RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @Valid @RequestBody PlaceOrderRequest request) {

    if (request.requiresPrice() && request.price() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price is required for LIMIT orders");
    }

    long priceTicks =
        request.requiresPrice() ? toTicks(request.price()) : 0L;
    long quantityLots = toLots(request.quantity());

    var command =
        new PlaceOrderCommand(
            idempotencyKey,
            request.symbol(),
            request.side(),
            request.type(),
            priceTicks,
            quantityLots,
            Instant.now());

    String orderId = placeOrderUseCase.place(command);
    return ResponseEntity.status(HttpStatus.CREATED).body(new PlaceOrderResponse(orderId));
  }

  @DeleteMapping("/orders/{orderId}")
  @Operation(
      summary = "Cancel a resting order",
      responses = {
        @ApiResponse(responseCode = "200", description = "Cancelled (or already gone — idempotent)")
      })
  public ResponseEntity<CancelOrderResponse> cancelOrder(
      @Parameter(description = "Client-generated UUID; repeating is idempotent")
          @RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @PathVariable String orderId) {

    var command = new CancelOrderCommand(idempotencyKey, orderId);
    boolean cancelled = cancelOrderUseCase.cancel(command);
    return ResponseEntity.ok(new CancelOrderResponse(orderId, cancelled));
  }

  private static long toTicks(BigDecimal price) {
    return price.multiply(BigDecimal.valueOf(TICK_MULTIPLIER)).longValueExact();
  }

  private static long toLots(BigDecimal quantity) {
    return quantity.longValueExact();
  }
}
