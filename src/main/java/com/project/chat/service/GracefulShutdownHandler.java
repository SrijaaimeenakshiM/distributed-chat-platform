package com.project.chat.service;

import com.project.chat.websocket.SessionRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GracefulShutdownHandler coordinates an orderly JVM shutdown.
 *
 * SHUTDOWN SEQUENCE (in @PreDestroy order, registered before SessionRegistry):
 * ─────────────────────────────────────────────────────────────────────────────
 *  1. Signal MessageProcessingService to stop accepting new messages.
 *  2. Wait up to drainTimeoutSeconds for the LinkedBlockingQueue to empty.
 *     This ensures in-flight messages are persisted and published before exit.
 *  3. SessionRegistry.@PreDestroy fires next (Spring orders beans): closes
 *     all open WebSocket sessions with GOING_AWAY close code so SockJS
 *     clients know to reconnect rather than spin-wait.
 *  4. Spring shuts down the remaining context (DB pool, Redis pool, etc.).
 *
 * WHY NOT rely solely on Spring's graceful shutdown (server.shutdown=graceful)?
 * Spring's graceful shutdown drains the Tomcat/Netty HTTP request queue but
 * knows nothing about our in-memory LinkedBlockingQueue. Without this handler,
 * messages sitting in the queue when the JVM exits are silently lost.
 *
 * The sequence guarantees:
 *   - No new messages accepted after shutdown signal
 *   - All enqueued messages are persisted and published (or timeout logged)
 *   - WebSocket clients get a clean close frame for graceful reconnect
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GracefulShutdownHandler {

    private final MessageProcessingService messageProcessingService;
    private final SessionRegistry sessionRegistry;

    @Value("${app.queue.drain-timeout-seconds:30}")
    private int drainTimeoutSeconds;

    @PreDestroy
    public void onShutdown() {
        log.info("╔═══════════════════════════════════════════════════════════╗");
        log.info("║  GracefulShutdownHandler: initiating controlled shutdown  ║");
        log.info("╚═══════════════════════════════════════════════════════════╝");

        long startMs = System.currentTimeMillis();

        // Step 1: Stop the producer side — no new messages enter the queue
        log.info("[Shutdown] Step 1: Signalling MessageProcessingService to stop accepting messages");
        messageProcessingService.initiateShutdown();

        // Step 2: Drain the LinkedBlockingQueue
        long queueSizeBefore = messageProcessingService.getQueueSize();
        log.info("[Shutdown] Step 2: Draining queue. Current size={}, timeout={}s",
                queueSizeBefore, drainTimeoutSeconds);

        try {
            boolean drained = messageProcessingService.awaitDrain(drainTimeoutSeconds);
            long elapsed = System.currentTimeMillis() - startMs;

            if (drained) {
                log.info("[Shutdown] Queue drained successfully in {}ms. " +
                        "Total messages sequenced: {}",
                        elapsed, messageProcessingService.getTotalMessagesSequenced());
            } else {
                long remaining = messageProcessingService.getQueueSize();
                log.warn("[Shutdown] Drain timed out after {}ms. {} messages may be lost. " +
                        "Consider increasing app.queue.drain-timeout-seconds.",
                        elapsed, remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Shutdown] Drain interrupted. Remaining queue size: {}",
                    messageProcessingService.getQueueSize());
        }

        // Step 3: Log active connections before SessionRegistry closes them
        int activeSessions = sessionRegistry.getActiveConnectionCount();
        log.info("[Shutdown] Step 3: {} active WebSocket sessions will be closed by SessionRegistry",
                activeSessions);

        // SessionRegistry.@PreDestroy handles actual session closing.
        // We just log here so the sequence is visible in logs.

        long totalMs = System.currentTimeMillis() - startMs;
        log.info("[Shutdown] Graceful shutdown complete in {}ms.", totalMs);
    }
}
