CREATE TABLE IF NOT EXISTS match_participants
(
    match_id
    UUID
    NOT
    NULL
    REFERENCES
    match_log
(
    match_id
) ON DELETE CASCADE,
    player_uuid UUID NOT NULL,
    session_id UUID REFERENCES player_sessions
(
    session_id
)
  ON DELETE SET NULL,
    PRIMARY KEY
(
    match_id,
    player_uuid
)
    );

CREATE INDEX IF NOT EXISTS idx_match_participants_player ON match_participants (player_uuid, match_id);
