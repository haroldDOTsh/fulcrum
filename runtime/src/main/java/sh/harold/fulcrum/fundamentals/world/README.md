# World Loading System

A registry-backed world cache that stores schematics and metadata in PostgreSQL through registry-service, extracts POIs straight from schematic markers, and pastes them through FAWE at runtime.

## Architecture Overview

- PostgreSQL is the source of truth, but game nodes reach it only through the registry-service world map store. Every map lives in the world_maps table with its .schem payload in a BYTEA column and lightweight JSON metadata.
- POIs are defined in the schematic. Builders place signs to mark POIs and an optional origin; the runtime parses those markers when caching and removes the sign blocks from the final schematic.
- Local cache: at startup WorldService downloads every map, sanitises the clipboard, sets the origin, writes a cleaned .schem into plugins/Fulcrum/world-cache/, and keeps POI descriptors in memory.
- Runtime pasting: WorldManager reads the cached schematic, pastes it with FAWE so the origin aligns to (0,64,0), and registers POIs with the shared POIRegistry so feature modules can react.
- Staff commands surface cached metadata and POI lists; /world save submits a world-map save request to registry-service instead of opening a database connection from the game node.

## Database Schema

    CREATE EXTENSION IF NOT EXISTS pgcrypto;

    CREATE TABLE world_maps (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        world_name VARCHAR(255) NOT NULL,
        display_name VARCHAR(255),
        map_metadata JSONB NOT NULL,   -- e.g. { "mapId": "arena_a", "gameId": "ctf", "author": "team" }
        schematic_data BYTEA NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE (world_name)
    );

Recommended indexes:

    CREATE INDEX idx_world_maps_map_id  ON world_maps ((map_metadata ->> 'mapId'));
    CREATE INDEX idx_world_maps_game_id ON world_maps ((map_metadata ->> 'gameId'));
    CREATE INDEX idx_world_maps_author  ON world_maps ((map_metadata ->> 'author'));

## Schematic Markers

Marker | Purpose | Notes
--- | --- | ---
[ORIGIN] | Defines the paste origin. The block containing the sign becomes (0,64,0) when pasted. Optional; defaults to clipboard origin/min corner.
[POI] | Declares a point of interest. Subsequent sign lines encode attributes in either key=value pairs or JSON. Requires type; optional keys like id, team, priority, etc.

Example sign:

    [POI]
    type=flag
    id=red_flag
    team=red,priority=1

During caching the sign is removed, the POI is stored with coordinates relative to the origin, and its metadata is exposed via PoiDefinition#metadata() and POIRegistry.

## Runtime Components

### WorldService
- Requires a `WorldMapStore` client. Paper uses the message-bus client; registry-service owns the PostgreSQL implementation.
- Streams every world-map record from the store, hydrates metadata, and runs the SchematicInspector.
- Writes cleaned schematics to plugins/Fulcrum/world-cache/<mapId>.schem and caches LoadedWorld objects containing metadata + POIs.
- Supports manual reloads through WorldFeature#refreshWorldCache().

### SchematicInspector
- Uses FAWE to load the schematic, scan sign NBT, strip markers, and set the clipboard origin.
- Returns POI definitions with relative coordinates so the runtime can register them after paste.

### WorldManager
- Loads a cached schematic, pastes it into a Bukkit world, then registers POIs with POIRegistry so Fulcrum modules can react.
- Clears pre-existing POIs for the world before registering the fresh set.

### Commands
- /world list – display cached maps with game and author metadata.
- /world info <name> – dump JSON metadata, cache path, and timestamps.
- /world pois <name> – list extracted POIs with relative coordinates.
- /world status – summary of cache counts and paths.

## Configuration Snippet

    authority:
      server-id: "registry-service"
      request-timeout-ms: 5000

## Content Pipeline Notes

1. Builders export maps as .schem files with origin/POI signs placed in-world.
2. A tooling step uploads the .schem binary into world_maps.schematic_data and writes map_metadata with keys such as mapId, gameId, and author.
3. On server start the runtime caches the map, strips the signs, and aligns the origin automatically.
4. When the map is pasted in-game, POIs are registered with POIRegistry so Fulcrum modules can attach behaviour.

## Example Metadata Payload

    {
      "mapId": "ctf_fortress",
      "gameId": "ctf",
      "author": "build-team",
      "rotation": "season_1"
    }

Keep map-level metadata small and declarative. Gameplay coordinates belong in the schematic through POI markers, not in the database JSON.
