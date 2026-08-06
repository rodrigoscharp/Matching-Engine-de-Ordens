package com.athena.adapter.rest.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.athena.adapter.rest.controller.PlaceOrderController;
import com.athena.trading.application.port.inbound.CancelOrderUseCase;
import com.athena.trading.application.port.inbound.PlaceOrderUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Status codes for requests that never reach a controller.
 *
 * <p>A catch-all {@code @ExceptionHandler(Exception.class)} turns Spring's "no handler for this
 * path" signal into a 500, so a plain typo in a URL reads to the caller — and to any alerting
 * built on 5xx rates — as a server fault.
 */
@WebMvcTest(PlaceOrderController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mvc;

  @MockBean private PlaceOrderUseCase placeOrderUseCase;
  @MockBean private CancelOrderUseCase cancelOrderUseCase;

  @Test
  @DisplayName("an unknown path is 404, not 500")
  void should_return_404_for_unknown_path() throws Exception {
    mvc.perform(get("/api/v1/no-such-route"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  @DisplayName("a wrong HTTP method is 405, not 500")
  void should_return_405_for_unsupported_method() throws Exception {
    mvc.perform(get("/api/v1/orders")).andExpect(status().isMethodNotAllowed());
  }

  @Test
  @DisplayName("an unsupported content type is 415, not 500")
  void should_return_415_for_unsupported_media_type() throws Exception {
    mvc.perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "key-1")
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .content("not json"))
        .andExpect(status().isUnsupportedMediaType());
  }
}
