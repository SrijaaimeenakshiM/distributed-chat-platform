package com.project.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.chat.dto.DTOs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * RedisMessageSubscriber — receives pub/sub messages from Redis and
 * delivers them to local STOMP subscribers on THIS server.
 *
 * CROSS-SERVER FAN-OUT FLOW:
 * ──────────────────────────
 *   Server A (message origin):
 *     1. Client sends STOMP → ChatController.sendMessage()
 *     2. MessageProcessingService.enqueue()
 *     3. Consumer thread: PERSIST to DB + PUBLISH to Redis chat:room:{id}
 *     4. Server A also broadcasts locally via STOMP /topic/room.{id}
 *
 *   Server B (subscriber, different JVM):
 *     5. RedisMessageListenerContainer receives from chat:room:*
 *     6. Calls this.handleMessage(payload, channel)
 *     7. Deserializes envelope → broadcasts to its own STOMP /topic/room.{id}
 *     8. Clients connected to Server B receive the message
 *
 * WHY NOT SKIP LOCAL BROADCAST ON ORIGIN SERVER:
 * The origin server broadcasts locally immediately (step 4) without waiting
 * for Redis round-trip (~1ms). This minimises latency for clients on the origin.
 * Redis pub/sub delivers to OTHER servers (step 7). The origin server will also
 * receive its own pub/sub message but can filter by sourceServerId if needed.
 * In practice, STOMP topic deduplication in SockJS handles duplicate delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisMessageSubscriber {

    private final SimpMessagingTemplate messagingTemplate;

    @Qualifier("redisObjectMapper")
    private final ObjectMapper objectMapper;

    @Value("${app.server-id}")
    private String serverId;

    /**
     * Called by Spring's RedisMessageListenerContainer when a message
     * arrives on any channel matching the pattern "chat:room:*".
     *
     * This method runs on the Redis listener thread (a dedicated platform
     * thread managed by the container). It must be fast — any heavy work
     * should be offloaded to a separate executor.
     *
     * @param message the raw JSON payload (UTF-8 string)
     * @param channel the Redis channel name, e.g. "chat:room:42"
     */
    public void handleMessage(String message, String channel) {
        try {
            log.debug("[RedisSubscriber] Received on channel={} length={}", channel, message.length());

            DTOs.RedisMessageEnvelope envelope =
                    objectMapper.readValue(message, DTOs.RedisMessageEnvelope.class);

            // Optional optimization: skip re-broadcasting if this server was the source.
            // The origin server already broadcast locally in MessageProcessingService.
            // Remove this check if you want guaranteed delivery even on the origin
            // (e.g., if local broadcast failed due to a STOMP error).
            if (serverId.equals(envelope.sourceServerId())) {
                log.debug("[RedisSubscriber] Skipping own message: sourceServerId={}", serverId);
                return;
            }

            // Build a ChatEvent and deliver to local STOMP subscribers
            DTOs.ChatEvent event = new DTOs.ChatEvent(
                    envelope.eventType(),
                    envelope.roomId(),
                    envelope.message(),
                    Instant.now(),
                    envelope.sourceServerId()   // preserve origin server id for client debugging
            );

            String stompDestination = "/topic/room." + envelope.roomId();
            messagingTemplate.convertAndSend(stompDestination, event);

            log.debug("[RedisSubscriber] Fan-out delivered: roomId={} from server={}",
                    envelope.roomId(), envelope.sourceServerId());

        } catch (Exception e) {
            // Never throw from a Redis listener — exceptions here kill the
            // listener container thread, severing ALL subsequent pub/sub delivery.
            log.error("[RedisSubscriber] Failed to process message on channel={}: {}",
                    channel, e.getMessage(), e);
        }
    }
}
