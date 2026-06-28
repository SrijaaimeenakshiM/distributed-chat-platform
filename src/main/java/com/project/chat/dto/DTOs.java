package com.project.chat.dto;

import com.project.chat.model.MemberRole;
import com.project.chat.model.MessageType;
import com.project.chat.model.UserStatus;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/**
 * DTOs.java — All data transfer objects as Java records.
 *
 * Records provide:
 *  - Immutable value semantics (all fields final)
 *  - Auto-generated equals/hashCode/toString
 *  - Compact constructor for validation
 *  - Zero boilerplate vs. Lombok @Value
 */
public final class DTOs {

    // ─── Auth ─────────────────────────────────────────────────────────────────

    public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50)
        String username,

        @NotBlank @Email
        String email,

        @NotBlank @Size(min = 8, max = 100)
        String password,

        @Size(max = 100)
        String displayName
    ) {}

    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
    ) {}

    public record AuthResponse(
        String token,
        String refreshToken,
        long expiresIn,
        UserSummary user
    ) {}

    // ─── Users ────────────────────────────────────────────────────────────────

    public record UserSummary(
        Long id,
        String username,
        String displayName,
        String avatarUrl,
        UserStatus status
    ) {}

    public record UserProfile(
        Long id,
        String username,
        String email,
        String displayName,
        String avatarUrl,
        Instant createdAt,
        Instant lastSeenAt
    ) {}

    // ─── Rooms ────────────────────────────────────────────────────────────────

    public record CreateRoomRequest(
        @NotBlank @Size(min = 2, max = 100)
        String name,

        @Size(max = 500)
        String description,

        boolean isPrivate,

        @Min(2) @Max(10000)
        int maxMembers
    ) {
        // Compact constructor: apply defaults
        public CreateRoomRequest {
            if (maxMembers == 0) maxMembers = 500;
        }
    }

    public record RoomResponse(
        Long id,
        String name,
        String description,
        boolean isPrivate,
        int maxMembers,
        int memberCount,
        Instant createdAt,
        UserSummary createdBy,
        MemberRole currentUserRole
    ) {}

    public record RoomSummary(
        Long id,
        String name,
        boolean isPrivate,
        int memberCount,
        Instant lastActivity
    ) {}

    // ─── Messages ─────────────────────────────────────────────────────────────

    /**
     * Inbound message from client via STOMP @MessageMapping.
     * The senderId and senderUsername are always set from the
     * authenticated principal — never trusted from the payload.
     */
    public record SendMessageRequest(
        @NotNull Long roomId,

        @NotBlank @Size(max = 4000)
        String content,

        MessageType type
    ) {
        public SendMessageRequest {
            if (type == null) type = MessageType.TEXT;
        }
    }

    public record MessageResponse(
        Long id,
        Long roomId,
        Long senderId,
        String senderUsername,
        String senderDisplayName,
        String content,
        MessageType type,
        Instant createdAt,
        Instant editedAt,
        boolean deleted,
        long sequenceNumber
    ) {}

    /**
     * Paginated history response using keyset pagination.
     * nextCursor is the id of the oldest message in this page —
     * pass it as `beforeId` in the next request to fetch older messages.
     * hasMore=false means the client has reached the beginning of history.
     */
    public record MessagePageResponse(
        List<MessageResponse> messages,
        Long nextCursor,
        boolean hasMore
    ) {}

    // ─── WebSocket Events ─────────────────────────────────────────────────────

    /**
     * Envelope published to /topic/room.{roomId} for all events.
     * The `type` field lets the React client dispatch to the right handler.
     */
    public record ChatEvent(
        String type,             // "MESSAGE" | "TYPING" | "PRESENCE" | "ROOM_EVENT"
        Long roomId,
        Object payload,
        Instant timestamp,
        String serverId          // which server instance originated this event
    ) {}

    public record TypingEvent(
        Long roomId,
        Long userId,
        String username,
        boolean isTyping
    ) {}

    public record PresenceEvent(
        Long userId,
        String username,
        UserStatus status,
        Instant timestamp
    ) {}

    // ─── Room Members ─────────────────────────────────────────────────────────

    public record RoomMemberResponse(
        Long userId,
        String username,
        String displayName,
        String avatarUrl,
        MemberRole role,
        Instant joinedAt,
        UserStatus status
    ) {}

    public record JoinRoomRequest(
        @NotNull Long roomId
    ) {}

    // ─── Health ───────────────────────────────────────────────────────────────

    public record HealthResponse(
        String serverId,
        String status,
        boolean redisUp,
        boolean postgresUp,
        int activeConnections,
        long totalMessagesProcessed,
        Instant timestamp
    ) {}

    // ─── Error ────────────────────────────────────────────────────────────────

    public record ErrorResponse(
        String error,
        String message,
        int status,
        Instant timestamp,
        String path
    ) {}

    // ─── Redis Pub/Sub envelope ────────────────────────────────────────────────

    /**
     * Payload published to Redis channel chat:room:{roomId}.
     * Every server instance receives this and delivers to its local sessions.
     * sourceServerId lets servers skip redelivery to their own sessions
     * if they already broadcast locally (optional optimisation).
     */
    public record RedisMessageEnvelope(
        Long roomId,
        String sourceServerId,
        MessageResponse message,
        String eventType     // "MESSAGE" | "ROOM_EVENT"
    ) {}

    // Private constructor prevents instantiation of the container class
    private DTOs() {}
}
