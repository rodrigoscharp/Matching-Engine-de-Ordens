package com.athena.adapter.ws.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket configuration.
 *
 * <p>Clients connect to {@code ws://host:8080/ws} (or with SockJS fallback at {@code /ws}).
 * Subscribe to {@code /topic/books/{SYMBOL}} to receive real-time order book snapshots pushed
 * every ~250ms by {@link com.athena.adapter.ws.streaming.BookStreamingService}.
 *
 * <p>Example STOMP subscription (JavaScript):
 * <pre>
 *   const client = new StompJs.Client({ brokerURL: 'ws://localhost:8080/ws' });
 *   client.onConnect = () => client.subscribe('/topic/books/PETR4', msg => console.log(msg.body));
 *   client.activate();
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableScheduling
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    // In-memory broker for topic subscriptions
    registry.enableSimpleBroker("/topic");
    // Client-to-server messages use /app prefix (reserved for future order submission via WS)
    registry.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws")
        .setAllowedOriginPatterns("*")
        .withSockJS(); // SockJS fallback for browsers without native WebSocket
  }
}
