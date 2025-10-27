-- Fulcrum world prop storage schema
CREATE
EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS world_props
(
    id
    UUID
    PRIMARY
    KEY
    DEFAULT
    gen_random_uuid
(
),
    prop_name VARCHAR
(
    255
) NOT NULL UNIQUE,
    display_name VARCHAR
(
    255
),
    prop_type VARCHAR
(
    64
) NOT NULL,
    prop_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    schematic_data BYTEA NOT NULL,
    inserted_at TIMESTAMPTZ NOT NULL DEFAULT NOW
(
),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW
(
)
    );

CREATE INDEX IF NOT EXISTS idx_world_props_type ON world_props (prop_type);
CREATE INDEX IF NOT EXISTS idx_world_props_game ON world_props ((prop_metadata ->> 'gameId'));
CREATE INDEX IF NOT EXISTS idx_world_props_usage ON world_props ((prop_metadata ->> 'usageKey'));
