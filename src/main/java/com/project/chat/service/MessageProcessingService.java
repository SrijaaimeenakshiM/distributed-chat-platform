package com.project.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.chat.dto.DTOs;
import com.project.chat.model.Message;
import com.project.chat.model.MessageType;
import com.project.chat.repository.MessageRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MessageProcessingService — the heart of the message pipeline.
 *
 * ARCHITECTURE OVERVIEW
 * ─────────────────────
 * When a client sends a message via STOMP, we must:
 *  1. Persist it to PostgreSQL (durable, slow ~5-20ms)
 *  2. Publish to Redis pub/sub (cross-server fan-out, fast ~1ms)
 *  3. Add to Redis sorted-set cache (recent message cache, fast ~1ms)
 *
 * Doing these sequentially would block the STOMP handler thread for 20+ms
 * and limit throughput. The pattern here separates receiving from processing:
 *
 *   STOMP Thread → enqueue(LinkedBlockingQueue) → return immediately
 *                                ↓
 *   Consumer Thread (virtual) → dequeue → CompletableFuture.allOf(persist, publish)
 *
 * KEY CONCURRENCY STRUCTURES
 * ──────────────────────────
 *
 * 1. LinkedBlockingQueue<ChatMessage>(10_000) — incomingMessages
 *    ─────────────────────────────────────────────────────────────
 *    WHY LinkedBlockingQueue:
 *    - Bounded capacity (10_000) provides back-pressure: if processing falls
 *      behind, offer() returns false and the caller can apply rate-limiting
 *      rather than unbounded memory growth (unlike an unbounded ArrayDeque).
 *    - LinkedBlockingQueue uses two separate ReentrantLocks: one for head
 *      (consumers) and one for tail (producers). This means producers and
 *      consumers never contend with each other — critical for high throughput.
 *    - ArrayBlockingQueue uses a single lock shared by producers AND consumers,
 *      making it slower under concurrent access. LinkedBlockingQueue wins here.
 *    WHY NOT Disruptor or LMAX:
 *    Disruptor is faster but requires a fixed power-of-2 ring buffer, zero-GC
 *    commitment, and CPU pinning. LinkedBlockingQueue is simpler to reason about,
 *    works with any JVM, and 10K message capacity is sufficient at our scale.
 *
 * 2. CompletableFuture.allOf(persistFuture, publishFuture)
 *    ────────────────────────────────────────────────────────
 *    WHY allOf (parallel) vs sequential:
 *    - DB persist (~10ms) and Redis publish (~1ms) are independent operations.
 *    - Sequential: total latency = 10ms + 1ms = 11ms
 *    - Parallel via allOf: total latency = max(10ms, 1ms) = 10ms
 *    - At 1000 msg/s this saves ~1000ms/s of processing latency.
 *    allOf() returns a future that completes when ALL supplied futures complete,
 *    enabling us to log, update caches, or notify the sender only after both
 *    operations succeed.
 *
 * 3. AtomicLong — messageSequenceCounter
 *    ─────────────────────────────────────
 *    WHY AtomicLong vs synchronized counter:
 *    getAndIncrement() is a single CPU atomic instruction (LOCK XADD on x86).
 *    No JVM monitor, no context switch, no thread parking. At 10K msg/s the
 *    difference vs synchronized accumulates to meaningful latency savings.
 *    Sequence numbers provide total ordering within a server restart cycle,
 *    enabling clients to detect dropped or out-of-order messages.
 *
 * 4. Virtual Threads for the consumer loop
 *    ──────────────────────────────────────
 *    WHY virtual thread for the consumer:
 *    The consumer calls BlockingQueue.take() which blocks when the queue is empty.
 *    On a platform thread, this blocks the OS thread (expensive: ~1MB stack).
 *    On a virtual thread, the JVM's scheduler parks the virtual thread (cheap:
 *    ~200 bytes) and re-mounts it when an element is available. This means the
 *    consumer loop is "free" in terms of OS thread consumption.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageProcessingService {

    private final MessageRepository messageRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Qualifier("dbWriteExecutor")
    private final ExecutorService dbWriteExecutor;

    @Qualifier("redisPublishExecutor")
    private final ExecutorService redisPublishExecutor;

    @Value("${app.server-id}")
    private String serverId;

    @Value("${app.queue.message-capacity:10000}")
    private int queueCapacity;

    @Value("${app.queue.drain-timeout-seconds:30}")
    private int drainTimeoutSeconds;

    @Value("${app.redis.message-cache-size:200}")
    private int messageCacheSize;

    /**
     * The bounded producer-consumer queue.
     * Capacity 10_000: each ChatMessage is ~500 bytes → max ~5MB in-memory queue.
     * Producers (STOMP handler): offer() — non-blocking, returns false if full.
     * Consumer (virtual thread):  take() — blocks until an element is available.
     *
     * INITIALISED in @PostConstruct because queueCapacity is injected by Spring
     * after construction; we cannot use it in a field initializer.
     */
    private LinkedBlockingQueue<QueuedMessage> incomingMessages;

    /**
     * Monotonically increasing sequence number for message ordering.
     * AtomicLong: lock-free CAS increment, no monitor synchronisation.
     * Resets on server restart — that's acceptable because DB ids provide
     * durable ordering; this sequence is for in-flight delivery ordering only.
     */
    private final AtomicLong messageSequenceCounter = new AtomicLong(0);

    /** Consumer thread handle — used for graceful shutdown coordination. */
    private Thread consumerThread;

    /** Flag to signal the consumer loop to stop after draining. */
    private volatile boolean shuttingDown = false;

    @PostConstruct
    public void init() {
        // Initialise the queue now that @Value fields are injected
        incomingMessages = new LinkedBlockingQueue<>(queueCapacity);

        // Start the consumer on a Java 21 virtual thread.
        // Virtual threads are ideal here: the consumer spends most of its time
        // parked at queue.take(), which is a blocking call. The JVM scheduler
        // efficiently unmounts the virtual thread during the wait.
        consumerThread = Thread.ofVirtual()
                .name("msg-consumer-virtual")
                .start(this::consumeLoop);

        log.info("[MessageProcessingService] Started. Queue capacity={}, serverId={}",
                queueCapacity, serverId);
    }

    // ─── Producer ────────────────────────────────────────────────────────────

    /**
     * Enqueue a message for async processing.
     * Called by ChatController on the STOMP handler thread — must return fast.
     *
     * Returns true if enqueued, false if queue is at capacity (back-pressure signal).
     * The caller should apply rate-limiting when this returns false.
     */
    public boolean enqueue(DTOs.SendMessageRequest request, Long senderId, String senderUsername) {
        long seq = messageSequenceCounter.getAndIncrement();  // AtomicLong: lock-free
        QueuedMessage qm = new QueuedMessage(request, senderId, senderUsername, seq, Instant.now());

        boolean accepted = incomingMessages.offer(qm);  // non-blocking offer
        if (!accepted) {
            log.warn("[MPS] Queue FULL (size={}) — dropping message from user={}. " +
                    "Consider scaling horizontally.", incomingMessages.size(), senderUsername);
        }
        return accepted;
    }

    // ─── Consumer Loop ───────────────────────────────────────────────────────

    /**
     * Runs on the virtual consumer thread.
     * Loops indefinitely, taking one message at a time and processing it.
     * On shutdown, drains remaining messages within the timeout window.
     */
    private void consumeLoop() {
        log.info("[MPS] Consumer loop started on thread: {} (virtual={})",
                Thread.currentThread().getName(),
                Thread.currentThread().isVirtual());

        while (!shuttingDown || !incomingMessages.isEmpty()) {
            try {
                // take() blocks (parks the virtual thread) until a message is available.
                // Poll with timeout during shutdown to check the shuttingDown flag.
                QueuedMessage qm = incomingMessages.poll(100, TimeUnit.MILLISECONDS);
                if (qm != null) {
                    processMessage(qm);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[MPS] Consumer thread interrupted — shutting down");
                break;
            } catch (Exception e) {
                log.error("[MPS] Unhandled error in consumer loop: {}", e.getMessage(), e);
                // Don't break the loop — log and continue processing next message
            }
        }
        log.info("[MPS] Consumer loop exited. Remaining in queue: {}", incomingMessages.size());
    }

    // ─── Message Processing Pipeline ─────────────────────────────────────────

    /**
     * Process a single message through the parallel persist + publish pipeline.
     *
     * CompletableFuture.allOf() runs DB persist and Redis publish CONCURRENTLY.
     * Both are submitted to their respective named thread pools, allowing the
     * consumer virtual thread to move on (or block at take()) while both
     * I/O operations proceed in parallel.
     *
     * Error handling: each future catches its own exception. If DB persist fails,
     * we still attempt Redis publish (for ephemeral delivery), but log the error.
     * If Redis publish fails, we fall back to local STOMP delivery only (other
     * servers won't receive this message until DB recovery).
     */
    private void processMessage(QueuedMessage qm) {
        Message entity = buildEntity(qm);

        // ── Parallel I/O operations ──────────────────────────────────────────

        // Future 1: Persist to PostgreSQL on the db-write thread pool
        CompletableFuture<Message> persistFuture = CompletableFuture
                .supplyAsync(() -> persistMessage(entity), dbWriteExecutor)
                .exceptionally(ex -> {
                    log.error("[MPS] DB persist failed for seq={}: {}", qm.sequenceNumber(), ex.getMessage());
                    return entity; // return unpersisted entity so downstream still has data
                });

        // Future 2: Publish to Redis pub/sub on the redis-publish thread pool
        CompletableFuture<Void> publishFuture = CompletableFuture
                .runAsync(() -> publishToRedis(qm, entity), redisPublishExecutor)
                .exceptionally(ex -> {
                    // Circuit pattern: Redis failure must NOT kill message delivery.
                    // Log and fall through; local STOMP delivery handled below.
                    log.error("[MPS] Redis publish failed for seq={} (circuit skip): {}",
                            qm.sequenceNumber(), ex.getMessage());
                    return null;
                });

        // ── allOf: wait for BOTH to complete, then cache + local-broadcast ───
        CompletableFuture.allOf(persistFuture, publishFuture)
                .thenAcceptAsync(ignored -> {
                    Message persisted = persistFuture.join();

                    // Cache in Redis sorted set for fast recent-message retrieval
                    cacheMessageInRedis(persisted, qm.request().roomId());

                    // Deliver locally via STOMP to sessions on THIS server.
                    // Cross-server delivery is handled by RedisMessageSubscriber.
                    broadcastToLocalSubscribers(persisted, qm);

                    log.debug("[MPS] Processed seq={} room={} user={}",
                            qm.sequenceNumber(), qm.request().roomId(), qm.senderUsername());

                }, redisPublishExecutor)  // run post-processing on redis pool (fast ops)
                .exceptionally(ex -> {
                    log.error("[MPS] Post-processing error for seq={}: {}", qm.sequenceNumber(), ex.getMessage());
                    return null;
                });
    }

    // ─── Persist ─────────────────────────────────────────────────────────────

    @Transactional
    private Message persistMessage(Message entity) {
        Message saved = messageRepository.save(entity);
        log.debug("[MPS] Persisted messageId={} seq={}", saved.getId(), saved.getSequenceNumber());
        return saved;
    }

    // ─── Redis Publish ────────────────────────────────────────────────────────

    /**
     * Publish message to Redis channel chat:room:{roomId}.
     * Every connected server subscribes to this channel via RedisMessageSubscriber,
     * enabling fan-out to sessions on other server instances.
     *
     * Circuit pattern: wrapped in try-catch so Redis failure is non-fatal.
     */
    private void publishToRedis(QueuedMessage qm, Message entity) {
        try {
            String channel = "chat:room:" + qm.request().roomId();
            DTOs.RedisMessageEnvelope envelope = new DTOs.RedisMessageEnvelope(
                    qm.request().roomId(),
                    serverId,
                    toMessageResponse(entity),
                    "MESSAGE"
            );
            String payload = objectMapper.writeValueAsString(envelope);
            stringRedisTemplate.convertAndSend(channel, payload);
            log.debug("[MPS] Published to Redis channel={}", channel);
        } catch (Exception e) {
            // Circuit pattern: log and continue. Don't re-throw.
            log.error("[MPS] Redis publish FAILED (continuing without cross-server fan-out): {}",
                    e.getMessage());
        }
    }

    // ─── Redis Cache ─────────────────────────────────────────────────────────

    /**
     * Cache message in a Redis sorted set for fast recent history retrieval.
     *
     * ZADD cache:room:{roomId} <timestamp_millis> <serialized_message>
     *
     * The score is the message timestamp in milliseconds, giving natural
     * chronological ordering. We trim to messageCacheSize entries after each
     * insert to bound memory usage (ZREMRANGEBYRANK removes oldest entries).
     */
    @Async("redisPublishExecutor")
    public void cacheMessageInRedis(Message message, Long roomId) {
        try {
            String cacheKey = "cache:room:" + roomId;
            String value = objectMapper.writeValueAsString(toMessageResponse(message));
            double score = message.getCreatedAt().toEpochMilli();

            stringRedisTemplate.opsForZSet().add(cacheKey, value, score);

            // Trim: keep only the most recent messageCacheSize entries
            // ZREMRANGEBYRANK removes from rank 0 (oldest) up to (size - limit - 1)
            Long size = stringRedisTemplate.opsForZSet().zCard(cacheKey);
            if (size != null && size > messageCacheSize) {
                stringRedisTemplate.opsForZSet().removeRange(cacheKey, 0, size - messageCacheSize - 1);
            }
        } catch (Exception e) {
            log.warn("[MPS] Redis cache update failed for roomId={}: {}", roomId, e.getMessage());
        }
    }

    // ─── Local STOMP Broadcast ────────────────────────────────────────────────

    /**
     * Deliver the message to local STOMP subscribers on THIS server.
     * Cross-server delivery happens via RedisMessageSubscriber receiving
     * the pub/sub message on the chat:room:* channel.
     */
    private void broadcastToLocalSubscribers(Message message, QueuedMessage qm) {
        DTOs.ChatEvent event = new DTOs.ChatEvent(
                "MESSAGE",
                qm.request().roomId(),
                toMessageResponse(message),
                Instant.now(),
                serverId
        );
        messagingTemplate.convertAndSend(
                "/topic/room." + qm.request().roomId(), event);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Message buildEntity(QueuedMessage qm) {
        return Message.builder()
                .roomId(qm.request().roomId())
                .senderId(qm.senderId())
                .senderUsername(qm.senderUsername())
                .content(qm.request().content())
                .type(qm.request().type() != null ? qm.request().type() : MessageType.TEXT)
                .sequenceNumber(qm.sequenceNumber())
                .build();
    }

    public DTOs.MessageResponse toMessageResponse(Message m) {
        return new DTOs.MessageResponse(
                m.getId(),
                m.getRoomId(),
                m.getSenderId(),
                m.getSenderUsername(),
                m.getSenderUsername(), // displayName fallback; enriched in ChatService
                m.getContent(),
                m.getType(),
                m.getCreatedAt(),
                m.getEditedAt(),
                m.isDeleted(),
                m.getSequenceNumber() != null ? m.getSequenceNumber() : 0L
        );
    }

    // ─── Metrics ─────────────────────────────────────────────────────────────

    public long getQueueSize()                  { return incomingMessages.size(); }
    public long getTotalMessagesSequenced()     { return messageSequenceCounter.get(); }

    // ─── Graceful Shutdown ────────────────────────────────────────────────────

    /**
     * Signal consumer to stop accepting new messages and drain the queue.
     * Called by GracefulShutdownHandler before JVM exit.
     */
    public void initiateShutdown() {
        log.info("[MPS] Shutdown initiated. Queue size: {}", incomingMessages.size());
        shuttingDown = true;
    }

    public boolean awaitDrain(int timeoutSeconds) throws InterruptedException {
        if (consumerThread != null) {
            consumerThread.join(timeoutSeconds * 1000L);
        }
        boolean drained = incomingMessages.isEmpty();
        log.info("[MPS] Drain complete. Queue empty: {}", drained);
        return drained;
    }

    // ─── Internal record for the queue ───────────────────────────────────────

    record QueuedMessage(
        DTOs.SendMessageRequest request,
        Long senderId,
        String senderUsername,
        long sequenceNumber,
        Instant enqueuedAt
    ) {}
}
