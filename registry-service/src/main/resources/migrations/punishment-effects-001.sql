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
