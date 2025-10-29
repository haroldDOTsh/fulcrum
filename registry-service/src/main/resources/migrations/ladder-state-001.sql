CREATE TABLE IF NOT EXISTS ladder_state
(
    player_uuid
    UUID
    NOT
    NULL,
    ladder
    VARCHAR
(
    32
) NOT NULL,
    rung INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY
(
    player_uuid,
    ladder
)
    );
