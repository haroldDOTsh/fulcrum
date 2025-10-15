CREATE TABLE IF NOT EXISTS player_match_history
(
    match_id
    UUID
    NOT
    NULL,
    player_uuid UUID NOT NULL,
    session_id
    UUID
    REFERENCES
    player_sessions
(
    session_id
) ON DELETE SET NULL,
    recorded_at BIGINT NOT NULL,
    PRIMARY KEY
(
    match_id,
    player_uuid
),
    FOREIGN KEY
(
    match_id,
    player_uuid
) REFERENCES match_participants
(
    match_id,
    player_uuid
)
  ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS idx_match_history_player ON player_match_history(player_uuid, recorded_at DESC);
