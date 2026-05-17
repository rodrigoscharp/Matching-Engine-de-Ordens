package com.athena.adapter.rest.controller;

import com.athena.adapter.rest.dto.BookSnapshotResponse;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for market data queries: book snapshot. Part of the query side of CQRS.
 *
 * <p>Returns an empty snapshot (not 404) for a symbol with no orders — an empty book is a valid
 * market state. Clients should use the {@code takenAt} field to detect staleness.
 */
@RestController
@RequestMapping("/api/v1/books")
@Tag(name = "Market Data", description = "Order book snapshots")
public class OrderBookController {

  private final GetBookSnapshotUseCase getBookSnapshotUseCase;

  public OrderBookController(GetBookSnapshotUseCase getBookSnapshotUseCase) {
    this.getBookSnapshotUseCase = getBookSnapshotUseCase;
  }

  @GetMapping("/{symbol}")
  @Operation(
      summary = "Get current order book snapshot",
      responses = {
        @ApiResponse(responseCode = "200", description = "Snapshot (may be empty)")
      })
  public ResponseEntity<BookSnapshotResponse> getSnapshot(@PathVariable String symbol) {
    var response =
        getBookSnapshotUseCase
            .getSnapshot(symbol)
            .map(BookSnapshotResponse::from)
            .orElseGet(() -> BookSnapshotResponse.empty(symbol.toUpperCase()));

    return ResponseEntity.ok(response);
  }
}
