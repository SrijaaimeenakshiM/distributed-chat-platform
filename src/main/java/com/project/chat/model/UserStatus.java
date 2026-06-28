package com.project.chat.model;

/**
 * UserStatus represents a user's current online presence state.
 * Stored in Redis as part of the presence:users hash (TTL 30s).
 * Clients receive status changes via STOMP /topic/presence.{roomId}.
 */
public enum UserStatus {
    /** User is connected and actively interacting */
    ONLINE,
    /** User is connected but idle (no activity for 5+ minutes) */
    AWAY,
    /** User has voluntarily set themselves as busy / Do Not Disturb */
    BUSY,
    /** No active WebSocket connection detected */
    OFFLINE
}
