CREATE TABLE IF NOT EXISTS match_log
(
    match_id
    UUID
    PRIMARY
    KEY,
    family
    VARCHAR
(
    128
) NOT NULL,
    variant VARCHAR
(
    128
),
    map_id VARCHAR
(
    128
),
    environment VARCHAR
(
    128
),
    slot_id VARCHAR
(
    128
),
    events JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at BIGINT NOT NULL,
    ended_at BIGINT NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_match_log_family_started_at ON match_log (family, started_at DESC);
