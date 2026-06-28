package com.project.chat.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "messages",
    indexes = {
        // Composite index optimised for keyset pagination:
        // WHERE room_id = ? AND id < ? ORDER BY id DESC LIMIT 50
        // The planner uses (room_id, id) together; DESC on id matches ORDER BY direction.
        @Index(name = "idx_messages_room_id_id",         columnList = "room_id, id DESC"),
        // Secondary index for time-range queries (history fetch by date)
        @Index(name = "idx_messages_room_created",       columnList = "room_id, created_at DESC"),
        // Index for looking up messages by sender
        @Index(name = "idx_messages_sender",             columnList = "sender_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "messages_seq")
    @SequenceGenerator(name = "messages_seq", sequenceName = "messages_id_seq", allocationSize = 100)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "sender_username", nullable = false, length = 50)
    private String senderUsername;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private MessageType type = MessageType.TEXT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    /**
     * Sequence number assigned by the MessageProcessingService's AtomicLong.
     * This provides a total ordering guarantee within a server restart cycle,
     * supplementing the DB-assigned id for in-flight messages.
     */
    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
