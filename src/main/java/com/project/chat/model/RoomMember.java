package com.project.chat.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "room_members",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_room_members_room_user",
        columnNames = {"room_id", "user_id"}
    ),
    indexes = {
        @Index(name = "idx_room_members_room",  columnList = "room_id"),
        @Index(name = "idx_room_members_user",  columnList = "user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "room_members_seq")
    @SequenceGenerator(name = "room_members_seq", sequenceName = "room_members_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private MemberRole role = MemberRole.MEMBER;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @PrePersist
    protected void onCreate() {
        joinedAt = Instant.now();
    }
}
