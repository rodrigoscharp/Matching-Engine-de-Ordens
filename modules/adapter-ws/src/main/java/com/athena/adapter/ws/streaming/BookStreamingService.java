package com.athena.adapter.ws.streaming;

import com.athena.adapter.ws.dto.BookUpdateMessage;
import com.athena.trading.application.port.inbound.GetBookSnapshotUseCase;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically pushes order book snapshots to WebSocket STOMP subscribers.
 *
 * <p>All symbols that have had at least one order placed are pushed every
 * {@code athena.ws.book-push-interval-ms} milliseconds (default 250ms).
 *
 * <p>STOMP topic: {@code /topic/books/{SYMBOL}} — clients subscribe to individual symbols.
 *
 * <p>This design uses polling rather than event-driven push to avoid tight coupling between the
 * single-writer matching thread and the WebSocket layer. The 250ms interval introduces at most
 * one tick of delay, which is acceptable for a book display (not for HFT clients — those should
 * use the gRPC streaming endpoint).
 */
@Service
public class BookStreamingService {

  private static final Logger log = LoggerFactory.getLogger(BookStreamingService.class);

  private final GetBookSnapshotUseCase bookSnapshotUseCase;
  // SimpMessageSendingOperations is an interface — mockable on any JDK
  private final SimpMessageSendingOperations messagingTemplate;

  @Value("${athena.ws.book-push-interval-ms:250}")
  private long pushIntervalMs;

  public BookStreamingService(
      GetBookSnapshotUseCase bookSnapshotUseCase,
      SimpMessageSendingOperations messagingTemplate) {
    this.bookSnapshotUseCase = Objects.requireNonNull(bookSnapshotUseCase);
    this.messagingTemplate = Objects.requireNonNull(messagingTemplate);
  }

  /**
   * Runs every {@code athena.ws.book-push-interval-ms}ms. Pushes all known symbol snapshots to
   * their respective STOMP topics. Subscribers that aren't connected simply don't receive the
   * message — no connection tracking needed at this layer.
   */
  @Scheduled(fixedDelayString = "${athena.ws.book-push-interval-ms:250}")
  public void pushBookUpdates() {
    var snapshots = bookSnapshotUseCase.getAllSnapshots();
    if (snapshots.isEmpty()) return;

    snapshots.forEach(
        (symbol, snapshot) -> {
          try {
            var message = BookUpdateMessage.from(snapshot);
            messagingTemplate.convertAndSend("/topic/books/" + symbol, message);
          } catch (Exception ex) {
            log.warn("Failed to push WebSocket update for symbol {}", symbol, ex);
          }
        });
  }
}
