-- PostgreSQL Migration Script for Fulcrum Authority Core
-- Version: 002
-- Description: Create canonical durable tables for player, rank, session, registry, and match data.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS player_profiles (
    player_id UUID PRIMARY KEY,
    username VARCHAR(16) NOT NULL,
    normalized_username VARCHAR(16) NOT NULL,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    online BOOLEAN NOT NULL DEFAULT FALSE,
    current_server TEXT,
    current_proxy TEXT,
    total_playtime_ms BIGINT NOT NULL DEFAULT 0,
    last_ip TEXT,
    profile_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_player_profiles_normalized_username
    ON player_profiles (normalized_username);
CREATE INDEX IF NOT EXISTS idx_player_profiles_online
    ON player_profiles (online) WHERE online = TRUE;

CREATE TABLE IF NOT EXISTS player_settings (
    player_id UUID NOT NULL REFERENCES player_profiles(player_id) ON DELETE CASCADE,
    namespace TEXT NOT NULL,
    setting_key TEXT NOT NULL,
    setting_value JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_id, namespace, setting_key)
);

CREATE TABLE IF NOT EXISTS player_cosmetics (
    player_id UUID NOT NULL REFERENCES player_profiles(player_id) ON DELETE CASCADE,
    cosmetic_key TEXT NOT NULL,
    cosmetic_state TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_id, cosmetic_key)
);

CREATE TABLE IF NOT EXISTS player_rank_projection (
    player_id UUID PRIMARY KEY,
    primary_rank TEXT NOT NULL,
    ranks TEXT[] NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS player_rank_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id UUID NOT NULL,
    rank TEXT NOT NULL,
    action TEXT NOT NULL,
    actor TEXT NOT NULL,
    reason TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_player_rank_audit_player_time
    ON player_rank_audit (player_id, created_at DESC);

CREATE TABLE IF NOT EXISTS player_punishments (
    punishment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id UUID NOT NULL,
    punishment_type TEXT NOT NULL,
    state TEXT NOT NULL,
    actor TEXT NOT NULL,
    reason TEXT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by TEXT,
    revoke_reason TEXT
);
CREATE INDEX IF NOT EXISTS idx_player_punishments_active
    ON player_punishments (player_id, punishment_type, expires_at)
    WHERE state = 'ACTIVE';

CREATE TABLE IF NOT EXISTS player_social_edges (
    owner_player_id UUID NOT NULL,
    target_player_id UUID NOT NULL,
    edge_type TEXT NOT NULL,
    state TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_player_id, target_player_id, edge_type)
);

CREATE TABLE IF NOT EXISTS player_sessions (
    session_id UUID PRIMARY KEY,
    player_id UUID NOT NULL,
    proxy_id TEXT,
    server_id TEXT,
    state TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    disconnect_reason TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_player_sessions_player_time
    ON player_sessions (player_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_player_sessions_active
    ON player_sessions (player_id) WHERE ended_at IS NULL;

CREATE TABLE IF NOT EXISTS registry_node_snapshots (
    node_id TEXT PRIMARY KEY,
    node_type TEXT NOT NULL,
    address TEXT NOT NULL,
    port INTEGER NOT NULL,
    role TEXT NOT NULL,
    state TEXT NOT NULL,
    capacity INTEGER NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS match_records (
    match_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id TEXT NOT NULL,
    map_id TEXT,
    server_id TEXT,
    slot_id TEXT,
    state TEXT NOT NULL,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_match_records_family_time
    ON match_records (family_id, created_at DESC);

CREATE TABLE IF NOT EXISTS match_participant_stats (
    match_id UUID NOT NULL REFERENCES match_records(match_id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    team_id TEXT,
    placement INTEGER,
    stats JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (match_id, player_id)
);
CREATE INDEX IF NOT EXISTS idx_match_participant_stats_player
    ON match_participant_stats (player_id, created_at DESC);

CREATE TABLE IF NOT EXISTS authority_commands (
    command_id UUID PRIMARY KEY,
    command_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    scope TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    deadline_at TIMESTAMPTZ,
    fencing_token TEXT,
    expected_revision BIGINT NOT NULL DEFAULT 0,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    accepted BOOLEAN NOT NULL,
    rejection_reason TEXT,
    result_message TEXT,
    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_authority_commands_scope_time
    ON authority_commands (scope, created_at DESC);

CREATE TABLE IF NOT EXISTS analytics_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type TEXT NOT NULL,
    player_id UUID,
    session_id UUID,
    match_id UUID,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_analytics_events_type_time
    ON analytics_events (event_type, created_at DESC);
