-- Fulcrum player session segment storage schema
CREATE TABLE IF NOT EXISTS player_session_segments
(
    session_id
    UUID
    NOT
    NULL,
    segment_index
    INTEGER
    NOT
    NULL,
    type
    VARCHAR
(
    64
) NOT NULL,
    context VARCHAR
(
    128
),
    environment VARCHAR
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
    ended_at BIGINT,
    metadata JSONB,
    PRIMARY KEY
(
    session_id,
    segment_index
)
    );

ALTER TABLE player_session_segments
    ADD COLUMN IF NOT EXISTS environment VARCHAR (128);

ALTER TABLE player_session_segments
    ADD COLUMN IF NOT EXISTS family VARCHAR (128);

ALTER TABLE player_session_segments
    ADD COLUMN IF NOT EXISTS variant VARCHAR (128);

ALTER TABLE player_session_segments
DROP
COLUMN IF EXISTS server_id;

CREATE INDEX IF NOT EXISTS idx_player_session_segments_player ON player_session_segments (session_id);
CREATE INDEX IF NOT EXISTS idx_player_session_segments_type ON player_session_segments (type);
