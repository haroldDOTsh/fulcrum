-- Fulcrum world map storage schema
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS world_maps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    world_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    map_metadata JSONB NOT NULL,
    schematic_data BYTEA NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (world_name)
);

CREATE INDEX IF NOT EXISTS idx_world_maps_map_id ON world_maps ((map_metadata ->> 'mapId'));
CREATE INDEX IF NOT EXISTS idx_world_maps_game_id ON world_maps ((map_metadata ->> 'gameId'));
CREATE INDEX IF NOT EXISTS idx_world_maps_author ON world_maps ((map_metadata ->> 'author'));
