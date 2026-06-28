package com.project.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * RateLimitService — Redis sliding-window rate limiter.
 *
 * ALGORITHM: Sliding Window Log
 * ──────────────────────────────
 * Unlike a fixed-window counter (which can allow 2× the limit at window boundaries),
 * the sliding window log provides accurate per-user rate limiting:
 *
 * For each user, we maintain a Redis sorted set keyed by rate:user:{username}:
 *   - Score:  timestamp in milliseconds (enables range queries by time)
 *   - Member: unique request ID (timestamp:random suffix)
 *
 * On each request:
 *   1. ZADD rate:user:{username} NX <now_ms> <uuid>   — add this request
 *   2. ZREMRANGEBYSCORE 0 <now_ms - window_ms>         — remove expired entries
 *   3. ZCARD rate:user:{username}                       — count requests in window
 *   4. EXPIRE rate:user:{username} <window_seconds>    — auto-cleanup idle keys
 *
 * If ZCARD > maxMessages → deny the request.
 *
 * This is executed as a Lua script for atomicity — all four Redis commands
 * execute as a single transaction, preventing TOCTOU race conditions where
 * two concurrent requests both see count < limit and both get allowed.
 *
 * COMPLEXITY: O(log N) for ZADD, O(log N + M) for ZREMRANGEBYSCORE
 * where N = window entries, M = number of expired entries removed.
 * In practice N is small (60 requests/minute) so this is effectively O(1).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.redis.rate-limit.window-seconds:60}")
    private int windowSeconds;

    @Value("${app.redis.rate-limit.max-messages:60}")
    private int maxMessages;

    /**
     * Lua script for atomic sliding-window rate limit check.
     *
     * KEYS[1]: rate:user:{username}  — the sorted set key
     * ARGV[1]: current timestamp in milliseconds
     * ARGV[2]: window start timestamp (now - windowMs)
     * ARGV[3]: TTL in seconds (auto-expire idle keys)
     * ARGV[4]: max allowed requests in window
     * ARGV[5]: unique member ID for this request (timestamp:nanoTime)
     *
     * Returns: 1 if allowed, 0 if rate-limited.
     *
     * The script is registered once and executed via EVALSHA (SHA hash),
     * avoiding repeated script transmission to Redis.
     */
    private static final String SLIDING_WINDOW_LUA = """
        local key       = KEYS[1]
        local now       = tonumber(ARGV[1])
        local windowStart = tonumber(ARGV[2])
        local ttl       = tonumber(ARGV[3])
        local limit     = tonumber(ARGV[4])
        local member    = ARGV[5]
        
        -- Remove entries outside the current window (score < windowStart)
        redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
        
        -- Count remaining entries in the window
        local count = redis.call('ZCARD', key)
        
        if count >= limit then
            -- Rate limit exceeded — do NOT add this request
            return 0
        end
        
        -- Add this request to the window
        redis.call('ZADD', key, now, member)
        
        -- Refresh TTL to prevent orphaned keys for idle users
        redis.call('EXPIRE', key, ttl)
        
        return 1
        """;

    private final DefaultRedisScript<Long> rateLimitScript = buildScript();

    private DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(SLIDING_WINDOW_LUA);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Check whether a user is within their rate limit.
     *
     * @param username the username to check
     * @return true if the request is allowed, false if rate-limited
     */
    public boolean isAllowed(String username) {
        try {
            String key = "rate:user:" + username;
            long nowMs = Instant.now().toEpochMilli();
            long windowStartMs = nowMs - (windowSeconds * 1000L);

            // Unique member: prevents ZADD NX from deduplicating rapid requests
            String member = nowMs + ":" + System.nanoTime();

            Long result = stringRedisTemplate.execute(
                    rateLimitScript,
                    List.of(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowStartMs),
                    String.valueOf(windowSeconds + 5),   // TTL slightly longer than window
                    String.valueOf(maxMessages),
                    member
            );

            boolean allowed = result != null && result == 1L;
            if (!allowed) {
                log.warn("[RateLimit] EXCEEDED for user={} window={}s limit={}/window",
                        username, windowSeconds, maxMessages);
            }
            return allowed;

        } catch (Exception e) {
            // Redis unavailable → fail open (allow the request) to prevent
            // Redis outages from blocking all chat traffic. Log the anomaly.
            log.error("[RateLimit] Redis error — failing OPEN for user={}: {}", username, e.getMessage());
            return true;
        }
    }

    /**
     * Get the current request count for a user within the sliding window.
     * Used by the health endpoint and admin APIs.
     */
    public long getCurrentCount(String username) {
        try {
            String key = "rate:user:" + username;
            long windowStartMs = Instant.now().toEpochMilli() - (windowSeconds * 1000L);

            // First remove stale entries, then count
            stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStartMs);
            Long count = stringRedisTemplate.opsForZSet().zCard(key);
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.warn("[RateLimit] getCurrentCount failed for user={}: {}", username, e.getMessage());
            return 0L;
        }
    }

    /**
     * Reset rate limit for a user (admin operation).
     */
    public void resetLimit(String username) {
        try {
            stringRedisTemplate.delete("rate:user:" + username);
            log.info("[RateLimit] Reset rate limit for user={}", username);
        } catch (Exception e) {
            log.error("[RateLimit] Reset failed for user={}: {}", username, e.getMessage());
        }
    }
}
