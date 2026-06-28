package com.project.chat.websocket;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SessionRegistry — the server-local source of truth for WebSocket sessions.
 *
 * DESIGN DECISION: Why not a simple synchronized Map?
 * ─────────────────────────────────────────────────────
 * A synchronized Map uses a single coarse-grained lock: every read AND write
 * blocks every other operation. Under high WebSocket concurrency (thousands of
 * connected clients) this creates a lock convoy where reader threads queue behind
 * writer threads for operations that could be served concurrently.
 *
 * We apply three distinct synchronisation strategies, each matched to its use case:
 *
 * 1. ConcurrentHashMap<String, Set<WebSocketSession>> — localSessions
 *    ─────────────────────────────────────────────────────────────────
 *    WHY: Segment-level locking (Java 8+: lock-free reads via CAS) means that
 *    reads on different username keys never contend. Under read-heavy workloads
 *    (message fan-out reads the session set for every message) this is a major win.
 *    Thread-safe at the map level; NOT thread-safe for the Set value.
 *
 * 2. ReentrantLock per WebSocketSession — sessionLocks
 *    ─────────────────────────────────────────────────
 *    WHY: WebSocketSession.sendMessage() is NOT thread-safe per the JSR-356 spec.
 *    If two threads attempt to write to the same session concurrently, the frame
 *    stream can be corrupted (partial writes, interleaved frames).
 *    A per-session lock ensures only ONE thread writes to a session at a time.
 *    Using ReentrantLock (vs synchronized block) allows tryLock() with timeout
 *    so a slow client cannot indefinitely block a message-delivery thread.
 *
 * 3. ReentrantReadWriteLock — roomMembershipLock
 *    ─────────────────────────────────────────────
 *    WHY: Room membership (roomId → Set<userId>) is read on every inbound message
 *    (to check whether the sender is a member) but written rarely (join/leave).
 *    A ReadWriteLock allows unlimited concurrent readers while ensuring writes
 *    are exclusive. This is the textbook use case: high read / low write ratio.
 *
 * 4. AtomicInteger — activeConnectionCount
 *    ──────────────────────────────────────
 *    WHY: A plain int++ is not atomic on modern CPUs due to read-modify-write
 *    gaps where another thread can observe a stale value. AtomicInteger uses
 *    CPU-level CAS (compare-and-swap) instructions for lock-free increment/
 *    decrement, avoiding any monitor synchronisation overhead for a counter
 *    that is updated on every connect and disconnect.
 */
@Component
@Slf4j
public class SessionRegistry {

    // ── 1. Local session map ─────────────────────────────────────────────────
    // ConcurrentHashMap: lock-free reads, segment-locked writes.
    // Maps username → Set of that user's open WebSocket sessions
    // (a user can be logged in from multiple browser tabs).
    private final ConcurrentHashMap<String, Set<WebSocketSession>> localSessions =
            new ConcurrentHashMap<>();

    // ── 2. Per-session write lock ─────────────────────────────────────────────
    // Maps sessionId → ReentrantLock. The lock must be held before calling
    // session.sendMessage() to prevent concurrent frame writes.
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks =
            new ConcurrentHashMap<>();

    // ── 3. Room membership lock ───────────────────────────────────────────────
    // roomMembership maps roomId → Set<username> for O(1) membership checks.
    // Protected by a ReadWriteLock: many threads read simultaneously;
    // join/leave events acquire the exclusive write lock.
    private final Map<Long, Set<String>> roomMembership = new HashMap<>();
    private final ReentrantReadWriteLock roomMembershipLock = new ReentrantReadWriteLock();

    // ── 4. Active connection counter ──────────────────────────────────────────
    // CAS-based lock-free counter — no monitor synchronisation needed.
    private final AtomicInteger activeConnectionCount = new AtomicInteger(0);

    // ── Track sessionId → username for cleanup on disconnect ─────────────────
    private final ConcurrentHashMap<String, String> sessionUserMap = new ConcurrentHashMap<>();

    // ─── Session Lifecycle ───────────────────────────────────────────────────

    /**
     * Register a new WebSocket session for the given username.
     * Called when STOMP CONNECT frame is received and authenticated.
     *
     * Thread-safety:
     *  - computeIfAbsent on ConcurrentHashMap is atomic.
     *  - The inner Set is wrapped in Collections.synchronizedSet because
     *    the map value itself is not thread-safe; multiple sessions for the
     *    same user could be added concurrently (e.g., two browser tabs opening
     *    simultaneously).
     */
    public void registerSession(String username, WebSocketSession session) {
        localSessions.computeIfAbsent(username,
                k -> Collections.synchronizedSet(new HashSet<>()))
                .add(session);

        // Create a dedicated lock for this session before anyone tries to write to it
        sessionLocks.put(session.getId(), new ReentrantLock());
        sessionUserMap.put(session.getId(), username);

        int count = activeConnectionCount.incrementAndGet();
        log.info("[SessionRegistry] CONNECT  user={} sessionId={} totalActive={}",
                username, session.getId(), count);
    }

    /**
     * Remove a WebSocket session when the connection is closed.
     * If this was the user's last session, removes the username entry.
     */
    public void removeSession(String sessionId) {
        String username = sessionUserMap.remove(sessionId);
        if (username == null) return;

        Set<WebSocketSession> sessions = localSessions.get(username);
        if (sessions != null) {
            sessions.removeIf(s -> s.getId().equals(sessionId));
            if (sessions.isEmpty()) {
                localSessions.remove(username);
            }
        }

        // Remove the per-session lock — this session no longer exists
        sessionLocks.remove(sessionId);

        int count = activeConnectionCount.decrementAndGet();
        log.info("[SessionRegistry] DISCONNECT user={} sessionId={} totalActive={}",
                username, sessionId, count);
    }

    /**
     * Send a message to a specific WebSocket session.
     *
     * Acquires the per-session ReentrantLock before writing.
     * Uses tryLock(200ms) instead of lock() to avoid indefinitely blocking
     * the calling thread if the client is slow to consume messages.
     * If the lock cannot be acquired, the message is dropped for this session
     * and a warning is logged (the Redis pub/sub will retry on reconnect).
     */
    public boolean sendToSession(WebSocketSession session,
                                  org.springframework.web.socket.TextMessage message) {
        if (!session.isOpen()) return false;

        ReentrantLock lock = sessionLocks.get(session.getId());
        if (lock == null) return false;

        // tryLock with timeout: prevents slow-client back-pressure from blocking
        // the message-delivery thread pool indefinitely.
        boolean acquired = false;
        try {
            acquired = lock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("[SessionRegistry] Lock timeout for sessionId={} — dropping frame",
                        session.getId());
                return false;
            }
            if (session.isOpen()) {
                session.sendMessage(message);
                return true;
            }
        } catch (IOException e) {
            log.error("[SessionRegistry] Send failed sessionId={}: {}", session.getId(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[SessionRegistry] Interrupted waiting for session lock: {}", session.getId());
        } finally {
            if (acquired) lock.unlock();
        }
        return false;
    }

    /**
     * Get all open sessions for a username.
     * Returns empty set if user has no active sessions on this server.
     * The caller must NOT modify the returned set.
     */
    public Set<WebSocketSession> getSessionsForUser(String username) {
        Set<WebSocketSession> sessions = localSessions.get(username);
        if (sessions == null) return Collections.emptySet();
        // Return a snapshot to avoid ConcurrentModificationException during iteration
        synchronized (sessions) {
            return new HashSet<>(sessions);
        }
    }

    /**
     * Get all usernames of users with at least one active session on this server.
     */
    public Set<String> getConnectedUsernames() {
        return Collections.unmodifiableSet(localSessions.keySet());
    }

    // ─── Room Membership ─────────────────────────────────────────────────────

    /**
     * Record that a user has joined a room.
     * Acquires the WRITE lock — exclusive, blocks all readers.
     * This is acceptable because join events are rare relative to reads.
     */
    public void addToRoom(Long roomId, String username) {
        roomMembershipLock.writeLock().lock();  // WRITE: exclusive lock
        try {
            roomMembership.computeIfAbsent(roomId, k -> new HashSet<>()).add(username);
            log.debug("[SessionRegistry] User {} joined room {}", username, roomId);
        } finally {
            roomMembershipLock.writeLock().unlock();
        }
    }

    /**
     * Record that a user has left a room.
     * Acquires the WRITE lock for the same reasons as addToRoom.
     */
    public void removeFromRoom(Long roomId, String username) {
        roomMembershipLock.writeLock().lock();  // WRITE: exclusive lock
        try {
            Set<String> members = roomMembership.get(roomId);
            if (members != null) {
                members.remove(username);
                if (members.isEmpty()) roomMembership.remove(roomId);
            }
        } finally {
            roomMembershipLock.writeLock().unlock();
        }
    }

    /**
     * Check whether a user is a member of a room.
     *
     * WHY READ LOCK vs synchronised:
     * Multiple threads can simultaneously check membership without blocking
     * each other. Only a concurrent join/leave (write lock) would cause
     * readers to wait. This is the primary benefit of ReadWriteLock over
     * a plain synchronized block, which would serialise all readers.
     */
    public boolean isInRoom(Long roomId, String username) {
        roomMembershipLock.readLock().lock();   // READ: shared, non-exclusive
        try {
            Set<String> members = roomMembership.get(roomId);
            return members != null && members.contains(username);
        } finally {
            roomMembershipLock.readLock().unlock();
        }
    }

    /**
     * Get the set of usernames currently registered as members of a room.
     * Returns a defensive copy; safe for iteration without holding the lock.
     */
    public Set<String> getRoomMembers(Long roomId) {
        roomMembershipLock.readLock().lock();   // READ: shared lock
        try {
            Set<String> members = roomMembership.get(roomId);
            return members != null ? new HashSet<>(members) : Collections.emptySet();
        } finally {
            roomMembershipLock.readLock().unlock();
        }
    }

    // ─── Metrics ─────────────────────────────────────────────────────────────

    /** Returns the live active WebSocket connection count for the health endpoint. */
    public int getActiveConnectionCount() {
        return activeConnectionCount.get();
    }

    public boolean hasLocalSession(String username) {
        Set<WebSocketSession> sessions = localSessions.get(username);
        return sessions != null && !sessions.isEmpty();
    }

    // ─── Shutdown ────────────────────────────────────────────────────────────

    /**
     * @PreDestroy: called by Spring before the application context is destroyed.
     * Closes all open WebSocket sessions so clients receive a proper close frame
     * instead of a TCP RST, enabling SockJS clients to reconnect gracefully.
     *
     * This is the "circuit breaker" for the session layer during rolling deploys:
     * sessions are drained before the JVM exits, complementing GracefulShutdownHandler's
     * message-queue drain.
     */
    @PreDestroy
    public void closeAllSessions() {
        log.info("[SessionRegistry] @PreDestroy: closing {} active WebSocket sessions",
                activeConnectionCount.get());

        localSessions.forEach((username, sessions) -> {
            synchronized (sessions) {
                sessions.forEach(session -> {
                    if (session.isOpen()) {
                        try {
                            session.close(org.springframework.web.socket.CloseStatus.GOING_AWAY);
                            log.debug("[SessionRegistry] Closed session for user={}", username);
                        } catch (IOException e) {
                            log.warn("[SessionRegistry] Failed to close session for user={}: {}",
                                    username, e.getMessage());
                        }
                    }
                });
            }
        });

        localSessions.clear();
        sessionLocks.clear();
        sessionUserMap.clear();
        log.info("[SessionRegistry] All sessions closed.");
    }
}
