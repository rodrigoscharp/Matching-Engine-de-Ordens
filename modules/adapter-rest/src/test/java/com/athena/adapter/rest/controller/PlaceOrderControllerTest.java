package com.athena.adapter.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.athena.adapter.rest.handler.GlobalExceptionHandler;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import com.athena.trading.domain.OrderId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlaceOrderController.class)
@Import(GlobalExceptionHandler.class)
class PlaceOrderControllerTest {

  @Autowired private MockMvc mvc;

  @MockBean private PlaceOrderUseCase placeOrderUseCase;
  @MockBean private CancelOrderUseCase cancelOrderUseCase;

  @Test
  void should_return_201_with_order_id_when_limit_buy_is_placed() throws Exception {
    var orderId = "550e8400-e29b-41d4-a716-446655440000";
    when(placeOrderUseCase.place(any())).thenReturn(orderId);

    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"symbol":"PETR4","side":"BUY","type":"LIMIT","price":"10.50","quantity":"100"}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderId").value(orderId));
  }

  @Test
  void should_return_201_when_market_order_is_placed_without_price() throws Exception {
    when(placeOrderUseCase.place(any())).thenReturn("550e8400-e29b-41d4-a716-446655440001");

    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "key-mkt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"symbol":"PETR4","side":"BUY","type":"MARKET","quantity":"100"}
                    """))
        .andExpect(status().isCreated());
  }

  @Test
  void should_return_400_when_idempotency_key_header_is_missing() throws Exception {
    mvc.perform(
            post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"symbol":"PETR4","side":"BUY","type":"LIMIT","price":"10.50","quantity":"100"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Missing Header"));
  }

  @Test
  void should_return_400_when_symbol_is_missing() throws Exception {
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"side":"BUY","type":"LIMIT","price":"10.50","quantity":"100"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"));
  }

  @Test
  void should_return_400_when_quantity_is_zero() throws Exception {
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"symbol":"PETR4","side":"BUY","type":"LIMIT","price":"10.50","quantity":"0"}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_400_when_price_has_too_many_decimal_places() throws Exception {
    when(placeOrderUseCase.place(any()))
        .thenThrow(new ArithmeticException("Exact conversion impossible"));

    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "key-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"symbol":"PETR4","side":"BUY","type":"LIMIT","price":"10.505","quantity":"100"}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return_200_with_cancelled_true_when_order_is_cancelled() throws Exception {
    var orderId = UUID.randomUUID().toString();
    when(cancelOrderUseCase.cancel(any())).thenReturn(true);

    mvc.perform(
            delete("/api/v1/orders/" + orderId)
                .header("Idempotency-Key", "cancel-key-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelled").value(true))
        .andExpect(jsonPath("$.orderId").value(orderId));
  }

  @Test
  void should_return_200_with_cancelled_false_when_order_not_found() throws Exception {
    when(cancelOrderUseCase.cancel(any())).thenReturn(false);

    mvc.perform(
            delete("/api/v1/orders/" + UUID.randomUUID())
                .header("Idempotency-Key", "cancel-key-2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancelled").value(false));
  }
}
