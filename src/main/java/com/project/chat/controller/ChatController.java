package com.project.chat.controller;

import com.project.chat.dto.DTOs;
import com.project.chat.exception.ChatExceptions;
import com.project.chat.model.User;
import com.project.chat.service.ChatService;
import com.project.chat.service.PresenceService;
import com.project.chat.model.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * ChatController handles all inbound STOMP WebSocket messages.
 *
 * CLIENT → SERVER destinations routed here (prefixed with /app):
 *   /app/chat.send      — send a message to a room
 *   /app/chat.typing    — broadcast a typing indicator
 *   /app/chat.join      — join a room (WebSocket-side; REST join also exists)
 *   /app/chat.leave     — leave a room
 *   /app/chat.presence  — update own presence status
 *
 * SERVER → CLIENT destinations (pushed by services):
 *   /topic/room.{id}    — all messages and events for a room
 *   /topic/presence     — global presence changes
 *   /user/queue/errors  — per-user error delivery
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;
    private final PresenceService presenceService;

    // ─── Send Message ────────────────────────────────────────────────────────

    /**
     * Handle a client's request to send a message.
     *
     * The authenticated Principal is injected by Spring from the STOMP CONNECT
     * frame — set in WebSocketConfig's ChannelInterceptor.
     *
     * Processing is async: this method enqueues and returns immediately.
     * The actual persist + publish happens in MessageProcessingService's
     * consumer virtual thread.
     */
    @MessageMapping("/chat.send")
    public void sendMessage(
            @Payload DTOs.SendMessageRequest request,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {

        String username = principal.getName();
        User user = getUserFromSession(headerAccessor);

        log.debug("[ChatController] /chat.send  user={} room={} type={}",
                username, request.roomId(), request.type());

        boolean enqueued = chatService.processIncomingMessage(request, user.getId(), username);

        if (!enqueued) {
            // Queue is full — notify the sender via their private error queue
            throw new ChatExceptions.ServiceUnavailableException(
                    "Server is temporarily overloaded. Please retry in a moment.");
        }
    }

    // ─── Typing Indicator ─────────────────────────────────────────────────────

    /**
     * Broadcast a typing indicator for the given room.
     * NOT persisted — ephemeral event only.
     * Rate-limited on the client side; no server-side limit here to keep latency minimal.
     */
    @MessageMapping("/chat.typing")
    public void typing(
            @Payload DTOs.TypingEvent typingEvent,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {

        User user = getUserFromSession(headerAccessor);
        log.debug("[ChatController] /chat.typing user={} room={} isTyping={}",
                principal.getName(), typingEvent.roomId(), typingEvent.isTyping());

        chatService.broadcastTyping(
                typingEvent.roomId(), user.getId(), principal.getName(), typingEvent.isTyping());
    }

    // ─── Join Room ────────────────────────────────────────────────────────────

    /**
     * Join a room via WebSocket.
     * Complements the REST POST /api/rooms/{id}/join for clients that
     * want to join and immediately start receiving events in the same
     * WebSocket session without an additional HTTP round-trip.
     */
    @MessageMapping("/chat.join")
    public void joinRoom(
            @Payload DTOs.JoinRoomRequest joinRequest,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {

        User user = getUserFromSession(headerAccessor);
        log.info("[ChatController] /chat.join user={} room={}", principal.getName(), joinRequest.roomId());

        chatService.joinRoom(joinRequest.roomId(), user.getId());
        presenceService.heartbeat(user.getId(), principal.getName());
    }

    // ─── Leave Room ───────────────────────────────────────────────────────────

    @MessageMapping("/chat.leave")
    public void leaveRoom(
            @Payload DTOs.JoinRoomRequest leaveRequest,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {

        User user = getUserFromSession(headerAccessor);
        log.info("[ChatController] /chat.leave user={} room={}", principal.getName(), leaveRequest.roomId());

        chatService.leaveRoom(leaveRequest.roomId(), user.getId());
    }

    // ─── Presence Status Update ───────────────────────────────────────────────

    /**
     * Allow clients to explicitly set their presence status (AWAY, BUSY, etc.).
     * ONLINE status is set automatically on CONNECT and message send.
     */
    @MessageMapping("/chat.presence")
    public void updatePresence(
            @Payload DTOs.PresenceEvent event,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor) {

        User user = getUserFromSession(headerAccessor);
        UserStatus status = event.status() != null ? event.status() : UserStatus.ONLINE;

        log.debug("[ChatController] /chat.presence user={} status={}", principal.getName(), status);
        presenceService.setStatus(user.getId(), principal.getName(), status);
    }

    // ─── Error Handler ────────────────────────────────────────────────────────

    /**
     * Catches exceptions thrown within @MessageMapping handlers and
     * routes the error to the sender's private /user/queue/errors destination.
     * Other connected clients do NOT see these errors.
     */
    @MessageExceptionHandler
    @SendToUser("/queue/errors")
    public DTOs.ErrorResponse handleException(Exception ex, Principal principal) {
        String username = principal != null ? principal.getName() : "unknown";
        log.warn("[ChatController] Error for user={}: {}", username, ex.getMessage());

        int status = 500;
        if (ex instanceof ChatExceptions.ForbiddenException)         status = 403;
        else if (ex instanceof ChatExceptions.NotFoundException)     status = 404;
        else if (ex instanceof ChatExceptions.RateLimitException)    status = 429;
        else if (ex instanceof ChatExceptions.BadRequestException)   status = 400;
        else if (ex instanceof ChatExceptions.ServiceUnavailableException) status = 503;

        return new DTOs.ErrorResponse(
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                status,
                java.time.Instant.now(),
                "websocket"
        );
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    /**
     * Retrieve the authenticated User entity from the WebSocket session attributes.
     * Attributes were set during the HTTP upgrade handshake by JwtHandshakeInterceptor.
     * We store the full User to avoid a DB round-trip on every message.
     */
    private User getUserFromSession(SimpMessageHeaderAccessor headerAccessor) {
        // userId stored as Long in session attributes at handshake time
        Object userIdObj = headerAccessor.getSessionAttributes() != null
                ? headerAccessor.getSessionAttributes().get("userId")
                : null;

        if (userIdObj == null) {
            throw new ChatExceptions.UnauthorizedException("No authenticated user in session");
        }

        Long userId = (Long) userIdObj;
        String username = (String) headerAccessor.getSessionAttributes().get("username");

        // Lightweight proxy — only id and username needed for most operations
        User proxy = new User();
        proxy.setId(userId);
        proxy.setUsername(username);
        return proxy;
    }
}
