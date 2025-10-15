-- Fulcrum player session logging schema
CREATE
EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS player_sessions
(
    session_id
    UUID
    PRIMARY
    KEY,
    player_uuid
    UUID
    NOT
    NULL,
    environment
    VARCHAR
(
    128
),
    family VARCHAR
(
    128
),
    variant VARCHAR
(
    128
),
    started_at BIGINT NOT NULL,
    ended_at BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

ALTER TABLE player_sessions
    ADD COLUMN IF NOT EXISTS environment VARCHAR (128);

ALTER TABLE player_sessions
    ADD COLUMN IF NOT EXISTS family VARCHAR (128);

ALTER TABLE player_sessions
    ADD COLUMN IF NOT EXISTS variant VARCHAR (128);

ALTER TABLE player_sessions
DROP
COLUMN IF EXISTS server_id;

ALTER TABLE player_sessions
DROP
COLUMN IF EXISTS last_updated_at;

ALTER TABLE player_sessions
DROP
COLUMN IF EXISTS updated_at;

CREATE INDEX IF NOT EXISTS idx_player_sessions_player_uuid ON player_sessions (player_uuid);
