CREATE TABLE IF NOT EXISTS player_match_history
(
    match_id
    UUID
    NOT
    NULL,
    session_id
    UUID
    NOT
    NULL
    REFERENCES
    player_sessions
(
    session_id
) ON DELETE CASCADE,
    player_uuid UUID NOT NULL,
    family VARCHAR
(
    128
) NOT NULL,
    variant VARCHAR
(
    128
) NOT NULL,
    map_id VARCHAR
(
    128
) NOT NULL,
    started_at BIGINT NOT NULL,
    ended_at BIGINT NOT NULL,
    PRIMARY KEY
(
    match_id,
    player_uuid
)
    );

CREATE INDEX IF NOT EXISTS idx_match_history_player ON player_match_history(player_uuid, ended_at DESC);
