-- PostgreSQL Migration Script for Generic World Loading System
-- Version: 001
-- Description: Create world_maps table for generic world management with inline schematic storage

-- Required for gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Generic world maps table
CREATE TABLE IF NOT EXISTS world_maps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    world_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    map_metadata JSONB NOT NULL,            -- Map-level metadata (mapId, gameId, author, etc.)
    schematic_data BYTEA NOT NULL,          -- Raw .schem payload
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(world_name)
);

-- Indexes for efficient querying
CREATE INDEX idx_world_maps_map_id ON world_maps ((map_metadata ->> 'mapId'));
CREATE INDEX idx_world_maps_game_id ON world_maps ((map_metadata ->> 'gameId'));
CREATE INDEX idx_world_maps_author ON world_maps ((map_metadata ->> 'author'));

-- Add update trigger for updated_at column
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_world_maps_updated_at
    BEFORE UPDATE ON world_maps
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
