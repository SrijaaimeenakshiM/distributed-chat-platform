-- ============================================================
-- V1__schema.sql  — Initial schema for Distributed Chat Platform
-- Managed by Flyway; do NOT edit after first deployment.
-- ============================================================

-- ─── Extensions ──────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid() if needed later

-- ─── Sequences ───────────────────────────────────────────────────────────────
-- Allocation sizes match the JPA @SequenceGenerator allocationSize values to
-- avoid unnecessary round-trips for high-insert-rate tables (messages).
CREATE SEQUENCE IF NOT EXISTS users_id_seq         START 1 INCREMENT 50;
CREATE SEQUENCE IF NOT EXISTS rooms_id_seq         START 1 INCREMENT 10;
CREATE SEQUENCE IF NOT EXISTS messages_id_seq      START 1 INCREMENT 100;
CREATE SEQUENCE IF NOT EXISTS room_members_id_seq  START 1 INCREMENT 50;

-- ─── Users ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL DEFAULT nextval('users_id_seq') PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100),
    avatar_url    VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at  TIMESTAMPTZ,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT chk_users_username_len CHECK (char_length(username) >= 3)
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users (username);
CREATE INDEX IF NOT EXISTS idx_users_email    ON users (email);

-- ─── Rooms ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rooms (
    id          BIGINT       NOT NULL DEFAULT nextval('rooms_id_seq') PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_private  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  BIGINT       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    max_members INT          NOT NULL DEFAULT 500,

    CONSTRAINT chk_rooms_name_len    CHECK (char_length(name) >= 2),
    CONSTRAINT chk_rooms_max_members CHECK (max_members > 0 AND max_members <= 10000)
);

CREATE INDEX IF NOT EXISTS idx_rooms_name       ON rooms (name);
CREATE INDEX IF NOT EXISTS idx_rooms_created_by ON rooms (created_by);

-- ─── Messages ────────────────────────────────────────────────────────────────
-- Composite index on (room_id, id DESC) is the cornerstone of keyset pagination:
--   SELECT * FROM messages WHERE room_id = $1 AND id < $2 ORDER BY id DESC LIMIT 50
-- The index eliminates both the filter scan AND the sort, giving O(log n) seeks.
--
-- Secondary index on (room_id, created_at DESC) supports date-range queries:
--   WHERE room_id = $1 AND created_at BETWEEN $2 AND $3
CREATE TABLE IF NOT EXISTS messages (
    id              BIGINT       NOT NULL DEFAULT nextval('messages_id_seq') PRIMARY KEY,
    room_id         BIGINT       NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    sender_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    sender_username VARCHAR(50)  NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(20)  NOT NULL DEFAULT 'TEXT',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    edited_at       TIMESTAMPTZ,
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    sequence_number BIGINT,

    CONSTRAINT chk_messages_content_not_empty CHECK (char_length(content) > 0),
    CONSTRAINT chk_messages_type CHECK (
        type IN ('TEXT','IMAGE','FILE','JOIN','LEAVE','TYPING','PRESENCE','ROOM_EVENT')
    )
);

-- Primary keyset pagination index: room + descending id scan
CREATE INDEX IF NOT EXISTS idx_messages_room_id_id
    ON messages (room_id, id DESC);

-- Time-range history index
CREATE INDEX IF NOT EXISTS idx_messages_room_created
    ON messages (room_id, created_at DESC);

-- Sender lookup index
CREATE INDEX IF NOT EXISTS idx_messages_sender
    ON messages (sender_id);

-- ─── Room Members ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS room_members (
    id                  BIGINT      NOT NULL DEFAULT nextval('room_members_id_seq') PRIMARY KEY,
    room_id             BIGINT      NOT NULL REFERENCES rooms(id)  ON DELETE CASCADE,
    user_id             BIGINT      NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
    role                VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_message_id BIGINT     REFERENCES messages(id) ON DELETE SET NULL,

    CONSTRAINT uq_room_members_room_user UNIQUE (room_id, user_id),
    CONSTRAINT chk_room_members_role CHECK (role IN ('OWNER','ADMIN','MEMBER'))
);

CREATE INDEX IF NOT EXISTS idx_room_members_room ON room_members (room_id);
CREATE INDEX IF NOT EXISTS idx_room_members_user ON room_members (user_id);

-- ─── Seed: General Channel ────────────────────────────────────────────────────
-- A default "general" room so the app is usable immediately after setup.
-- Created by a system user (id=1) that must be inserted first.
INSERT INTO users (id, username, email, password_hash, display_name, is_active)
VALUES (1, 'system', 'system@chat.internal',
        '$2a$12$PLACEHOLDER_DO_NOT_LOGIN', 'System', FALSE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO rooms (id, name, description, is_private, created_by, max_members)
VALUES (1, 'general', 'The default public channel for everyone', FALSE, 1, 10000)
ON CONFLICT DO NOTHING;

-- ─── Update function for updated_at ──────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rooms_updated_at
    BEFORE UPDATE ON rooms
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
