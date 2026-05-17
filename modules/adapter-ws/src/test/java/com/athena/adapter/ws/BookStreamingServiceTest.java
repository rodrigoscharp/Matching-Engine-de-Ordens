package com.athena.adapter.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.athena.adapter.ws.streaming.BookStreamingService;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import com.athena.trading.domain.OrderBookSnapshot;
import com.athena.trading.domain.Symbol;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

@ExtendWith(MockitoExtension.class)
class BookStreamingServiceTest {

  @Mock private GetBookSnapshotUseCase bookSnapshotUseCase;
  // Interface — Mockito can always mock interfaces, even on JDK 25
  @Mock private SimpMessageSendingOperations messagingTemplate;
  @InjectMocks private BookStreamingService streamingService;

  @Test
  void should_not_push_when_no_snapshots_available() {
    when(bookSnapshotUseCase.getAllSnapshots()).thenReturn(Map.of());

    streamingService.pushBookUpdates();

    verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
  }

  @Test
  void should_push_snapshot_to_correct_topic() {
    var snapshot =
        new OrderBookSnapshot(Symbol.of("PETR4"), List.of(), List.of(), Instant.now());
    when(bookSnapshotUseCase.getAllSnapshots()).thenReturn(Map.of("PETR4", snapshot));

    streamingService.pushBookUpdates();

    verify(messagingTemplate)
        .convertAndSend(eq("/topic/books/PETR4"), any(Object.class));
  }

  @Test
  void should_push_to_separate_topics_for_each_symbol() {
    var snap1 = new OrderBookSnapshot(Symbol.of("PETR4"), List.of(), List.of(), Instant.now());
    var snap2 = new OrderBookSnapshot(Symbol.of("VALE3"), List.of(), List.of(), Instant.now());
    when(bookSnapshotUseCase.getAllSnapshots())
        .thenReturn(Map.of("PETR4", snap1, "VALE3", snap2));

    streamingService.pushBookUpdates();

    verify(messagingTemplate).convertAndSend(eq("/topic/books/PETR4"), any(Object.class));
    verify(messagingTemplate).convertAndSend(eq("/topic/books/VALE3"), any(Object.class));
  }
}
