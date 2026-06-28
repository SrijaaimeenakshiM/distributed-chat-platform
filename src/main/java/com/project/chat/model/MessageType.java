package com.project.chat.model;

/**
 * MessageType distinguishes user-generated content from system events.
 * The type field drives rendering on the client side:
 *  - TEXT/IMAGE/FILE: rendered as chat bubbles
 *  - JOIN/LEAVE:      rendered as system event pills
 *  - TYPING:          ephemeral; never persisted to DB
 *  - PRESENCE:        ephemeral; presence state changes
 */
public enum MessageType {
    /** Plain text message from a user */
    TEXT,
    /** Message contains an image URL */
    IMAGE,
    /** Message contains a file attachment URL */
    FILE,
    /** System event: a user joined the room */
    JOIN,
    /** System event: a user left the room */
    LEAVE,
    /** Ephemeral typing indicator (not persisted) */
    TYPING,
    /** Ephemeral presence update (not persisted) */
    PRESENCE,
    /** System event: room was created or updated */
    ROOM_EVENT
}
