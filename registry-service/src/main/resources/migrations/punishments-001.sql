CREATE TABLE IF NOT EXISTS punishments
(
    punishment_id
    UUID
    PRIMARY
    KEY,
    player_uuid
    UUID
    NOT
    NULL,
    player_name
    TEXT,
    staff_uuid
    UUID
    NOT
    NULL,
    staff_name
    TEXT,
    ladder
    VARCHAR
(
    32
) NOT NULL,
    reason VARCHAR
(
    64
) NOT NULL,
    rung_before INTEGER NOT NULL,
    rung_after INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    status VARCHAR
(
    16
) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_punishments_player ON punishments (player_uuid);

CREATE TABLE IF NOT EXISTS punishment_effects
(
    punishment_id
    UUID
    NOT
    NULL
    REFERENCES
    punishments
(
    punishment_id
) ON DELETE CASCADE,
    effect_order INTEGER NOT NULL,
    type VARCHAR
(
    32
) NOT NULL,
    duration_seconds BIGINT,
    expires_at TIMESTAMPTZ,
    message TEXT,
    PRIMARY KEY
(
    punishment_id,
    effect_order
)
    );

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
