package com.project.chat.controller;

import com.project.chat.dto.DTOs;
import com.project.chat.service.MessageProcessingService;
import com.project.chat.websocket.SessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@Slf4j
class HealthController {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final SessionRegistry sessionRegistry;
    private final MessageProcessingService messageProcessingService;

    @Value("${app.server-id}")
    private String serverId;

    /**
     * Custom health endpoint exposing application-level metrics.
     * Nginx upstream health checks hit this endpoint.
     * <p>
     * Returns 200 if both Redis and PostgreSQL are reachable.
     * Returns 503 if either dependency is down.
     */
    @GetMapping("/health")
    public ResponseEntity<DTOs.HealthResponse> health() {
        boolean redisUp = checkRedis();
        boolean postgresUp = checkPostgres();

        DTOs.HealthResponse resp = new DTOs.HealthResponse(
                serverId,
                (redisUp && postgresUp) ? "UP" : "DEGRADED",
                redisUp,
                postgresUp,
                sessionRegistry.getActiveConnectionCount(),
                messageProcessingService.getTotalMessagesSequenced(),
                Instant.now()
        );

        HttpStatus status = (redisUp && postgresUp) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(resp);
    }

    private boolean checkRedis() {
        try {
            stringRedisTemplate.opsForValue().get("health:ping");
            return true;
        } catch (Exception e) {
            log.warn("[Health] Redis check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkPostgres() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("[Health] PostgreSQL check failed: {}", e.getMessage());
            return false;
        }
    }
}
