package com.project.chat.service;

import com.project.chat.dto.DTOs;
import com.project.chat.model.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * PresenceService manages user online/offline state using Redis HSET with TTL.
 *
 * DESIGN: Redis Presence Hash
 * ───────────────────────────
 * Key:   presence:users          (global hash)
 * Field: {userId}
 * Value: {serverId}:{timestamp}:{status}
 * TTL:   Set on the individual field's parent key (30s)
 *
 * Each heartbeat call refreshes the TTL. If a client disconnects without
 * sending an OFFLINE event, the TTL expiry naturally transitions the user
 * to OFFLINE after 30 seconds — no cleanup process needed.
 *
 * HSET presence:users {userId} "{serverId}:{timestamp}:{status}"
 * EXPIRE presence:users 300   (rolling 5-minute key TTL as safety net)
 *
 * For per-field TTL (Redis 7.4+), HEXPIRE would be ideal. We simulate it
 * by storing the timestamp in the value and checking freshness on read.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.redis.presence-ttl-seconds:30}")
    private int presenceTtlSeconds;

    @Value("${app.server-id}")
    private String serverId;

    private static final String PRESENCE_KEY = "presence:users";
    private static final int PRESENCE_KEY_TTL_SECONDS = 300; // key-level safety net TTL

    // ─── Heartbeat ───────────────────────────────────────────────────────────

    /**
     * Record or refresh a user's online presence in Redis.
     * Called on every message send and on STOMP CONNECT.
     *
     * Runs on the "presence" executor to avoid blocking the caller.
     * HSET is O(1) and non-blocking in Redis; the @Async keeps the
     * calling thread (STOMP handler) free for the next message.
     */
    @Async("presenceExecutor")
    public void heartbeat(Long userId, String username) {
        try {
            String value = buildPresenceValue(serverId, Instant.now(), UserStatus.ONLINE);
            stringRedisTemplate.opsForHash().put(PRESENCE_KEY, userId.toString(), value);
            // Refresh the key-level TTL on every heartbeat
            stringRedisTemplate.expire(PRESENCE_KEY, PRESENCE_KEY_TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("[Presence] Heartbeat: userId={} username={}", userId, username);
        } catch (Exception e) {
            // Presence is best-effort — Redis failure must not affect message delivery
            log.warn("[Presence] Heartbeat failed for userId={}: {}", userId, e.getMessage());
        }
    }

    // ─── Status Update ───────────────────────────────────────────────────────

    /**
     * Explicitly set a user's status (ONLINE/AWAY/BUSY/OFFLINE).
     * Broadcasts the change to all rooms the user is in via STOMP.
     */
    @Async("presenceExecutor")
    public void setStatus(Long userId, String username, UserStatus status) {
        try {
            if (status == UserStatus.OFFLINE) {
                // Remove from hash on explicit OFFLINE
                stringRedisTemplate.opsForHash().delete(PRESENCE_KEY, userId.toString());
            } else {
                String value = buildPresenceValue(serverId, Instant.now(), status);
                stringRedisTemplate.opsForHash().put(PRESENCE_KEY, userId.toString(), value);
                stringRedisTemplate.expire(PRESENCE_KEY, PRESENCE_KEY_TTL_SECONDS, TimeUnit.SECONDS);
            }

            // Broadcast presence event to all topic subscribers
            DTOs.PresenceEvent event = new DTOs.PresenceEvent(userId, username, status, Instant.now());
            messagingTemplate.convertAndSend("/topic/presence", event);

            log.info("[Presence] Status change: userId={} username={} status={}", userId, username, status);
        } catch (Exception e) {
            log.warn("[Presence] setStatus failed for userId={}: {}", userId, e.getMessage());
        }
    }

    // ─── Status Query ─────────────────────────────────────────────────────────

    /**
     * Get a user's current status.
     * Reads from Redis HGET and checks timestamp freshness.
     * If the stored timestamp is older than presenceTtlSeconds, treat as OFFLINE.
     */
    public UserStatus getUserStatus(Long userId) {
        try {
            Object raw = stringRedisTemplate.opsForHash().get(PRESENCE_KEY, userId.toString());
            if (raw == null) return UserStatus.OFFLINE;

            String[] parts = raw.toString().split(":");
            if (parts.length < 3) return UserStatus.OFFLINE;

            // Parts: serverId:timestamp:status
            // serverId may contain '-' so we parse from the end
            String statusStr = parts[parts.length - 1];
            long timestampMs = Long.parseLong(parts[parts.length - 2]);
            Instant recordedAt = Instant.ofEpochMilli(timestampMs);

            // Freshness check: if older than TTL, treat as OFFLINE
            if (Instant.now().minusSeconds(presenceTtlSeconds).isAfter(recordedAt)) {
                return UserStatus.OFFLINE;
            }

            return UserStatus.valueOf(statusStr);
        } catch (Exception e) {
            log.debug("[Presence] Status query failed for userId={}: {}", userId, e.getMessage());
            return UserStatus.OFFLINE;
        }
    }

    // ─── Scheduled Cleanup ───────────────────────────────────────────────────

    /**
     * Periodically scan and remove stale presence entries.
     * Runs every presenceTtlSeconds/2 to catch entries whose timestamps
     * are stale but whose parent key hasn't expired yet.
     *
     * In a production deployment with Redis 7.4+, this would be replaced
     * by HEXPIRE per-field TTL for O(1) expiry without scanning.
     */
    @Scheduled(fixedDelayString = "${app.redis.presence-ttl-seconds:30}000")
    public void cleanupStalePresence() {
        try {
            var entries = stringRedisTemplate.opsForHash().entries(PRESENCE_KEY);
            Instant staleThreshold = Instant.now().minusSeconds(presenceTtlSeconds);
            int removed = 0;

            for (var entry : entries.entrySet()) {
                String raw = entry.getValue().toString();
                String[] parts = raw.split(":");
                if (parts.length >= 2) {
                    try {
                        long timestampMs = Long.parseLong(parts[parts.length - 2]);
                        if (staleThreshold.isAfter(Instant.ofEpochMilli(timestampMs))) {
                            stringRedisTemplate.opsForHash().delete(PRESENCE_KEY, entry.getKey());
                            removed++;
                        }
                    } catch (NumberFormatException ignored) {
                        // Malformed entry — remove it
                        stringRedisTemplate.opsForHash().delete(PRESENCE_KEY, entry.getKey());
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                log.debug("[Presence] Cleanup removed {} stale entries", removed);
            }
        } catch (Exception e) {
            log.warn("[Presence] Cleanup failed: {}", e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String buildPresenceValue(String serverId, Instant timestamp, UserStatus status) {
        // Format: serverId:epochMilli:STATUS
        return serverId + ":" + timestamp.toEpochMilli() + ":" + status.name();
    }

    /**
     * Remove user from presence on disconnect.
     * Called by STOMP disconnect event listener.
     */
    @Async("presenceExecutor")
    public void markOffline(Long userId, String username) {
        setStatus(userId, username, UserStatus.OFFLINE);
    }
}
