package com.project.chat.model;

/**
 * MemberRole defines the permission level of a user within a room.
 * Permissions are checked in ChatService before mutating room state.
 */
public enum MemberRole {
    /** Room creator; can delete the room and manage all members */
    OWNER,
    /** Elevated permissions: pin messages, remove members, update room info */
    ADMIN,
    /** Default role: can send/read messages and invite others to public rooms */
    MEMBER
}
