-- PostgreSQL Migration Script for Fulcrum Data Authority
-- Version: 001
-- Description: Create the clean-break current-state Fulcrum data schema.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TABLE IF NOT EXISTS world_maps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    world_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    map_metadata JSONB NOT NULL,
    schematic_data BYTEA NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(world_name)
);

CREATE INDEX IF NOT EXISTS idx_world_maps_map_id ON world_maps ((map_metadata ->> 'mapId'));
CREATE INDEX IF NOT EXISTS idx_world_maps_game_id ON world_maps ((map_metadata ->> 'gameId'));
CREATE INDEX IF NOT EXISTS idx_world_maps_author ON world_maps ((map_metadata ->> 'author'));

DROP TRIGGER IF EXISTS update_world_maps_updated_at ON world_maps;

CREATE TRIGGER update_world_maps_updated_at
    BEFORE UPDATE ON world_maps
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    snapshot_id UUID NOT NULL,
    snapshot_source TEXT NOT NULL,
    snapshot_version INTEGER NOT NULL,
    snapshot_fingerprint TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_registry_node_snapshots_attestation
    ON registry_node_snapshots (snapshot_source, updated_at DESC);

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
    revision BIGINT NOT NULL DEFAULT 0,
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
    schema_version INTEGER NOT NULL DEFAULT 1,
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
    payload_hash TEXT,
    command_fingerprint TEXT,
    provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_event_id UUID,
    result_event_type TEXT,
    verified_principal TEXT NOT NULL,
    command_domain TEXT NOT NULL,
    command_topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_authority_commands_scope_time
    ON authority_commands (scope, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_commands_fingerprint
    ON authority_commands (command_fingerprint);
CREATE INDEX IF NOT EXISTS idx_authority_commands_origin_time
    ON authority_commands ((provenance ->> 'originNode'), created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_commands_result_event
    ON authority_commands (result_event_id)
    WHERE result_event_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_commands_verified_principal_time
    ON authority_commands (verified_principal, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_commands_route_time
    ON authority_commands (command_domain, partition_key, created_at DESC);

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

CREATE TABLE IF NOT EXISTS authority_aggregate_versions (
    scope TEXT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    last_fencing_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS authority_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command_id UUID NOT NULL REFERENCES authority_commands(command_id) ON DELETE CASCADE,
    aggregate_scope TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    delivery_status TEXT NOT NULL DEFAULT 'PENDING',
    delivery_attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    hash_version INTEGER NOT NULL DEFAULT 1,
    previous_chain_hash TEXT,
    chain_hash TEXT,
    command_domain TEXT NOT NULL,
    event_topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_authority_events_scope_revision UNIQUE (aggregate_scope, revision)
);
CREATE INDEX IF NOT EXISTS idx_authority_events_pending
    ON authority_events (next_attempt_at, created_at)
    WHERE delivery_status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_authority_events_aggregate
    ON authority_events (aggregate_scope, revision);
CREATE UNIQUE INDEX IF NOT EXISTS idx_authority_events_scope_chain_hash
    ON authority_events (aggregate_scope, chain_hash)
    WHERE chain_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_events_previous_chain_hash
    ON authority_events (previous_chain_hash)
    WHERE previous_chain_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_events_route_revision
    ON authority_events (command_domain, partition_key, revision);

CREATE TABLE IF NOT EXISTS authority_state_snapshots (
    aggregate_scope TEXT PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    command_id UUID NOT NULL REFERENCES authority_commands(command_id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES authority_events(event_id) ON DELETE CASCADE,
    event_created_at TIMESTAMPTZ,
    event_fingerprint TEXT,
    event_chain_hash TEXT,
    state_fingerprint TEXT,
    state_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    command_domain TEXT NOT NULL,
    state_topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_authority_state_snapshots_event_time
    ON authority_state_snapshots (event_created_at DESC)
    WHERE event_created_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_state_snapshots_state_fingerprint
    ON authority_state_snapshots (state_fingerprint)
    WHERE state_fingerprint IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_state_snapshots_event_chain_hash
    ON authority_state_snapshots (event_chain_hash)
    WHERE event_chain_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_state_snapshots_route
    ON authority_state_snapshots (command_domain, partition_key);

CREATE TABLE IF NOT EXISTS authority_idempotency_conflicts (
    conflict_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_command_id UUID REFERENCES authority_commands(command_id) ON DELETE SET NULL,
    attempted_command_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    command_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    scope TEXT NOT NULL,
    expected_fingerprint TEXT,
    actual_fingerprint TEXT NOT NULL,
    rejection_reason TEXT NOT NULL,
    verified_principal TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_authority_idempotency_conflicts_key_time
    ON authority_idempotency_conflicts (idempotency_key, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_idempotency_conflicts_principal_time
    ON authority_idempotency_conflicts (verified_principal, created_at DESC);

CREATE TABLE IF NOT EXISTS authority_event_consumer_cursors (
    consumer_name TEXT PRIMARY KEY,
    last_event_id UUID NOT NULL REFERENCES authority_events(event_id),
    last_event_created_at TIMESTAMPTZ NOT NULL,
    last_aggregate_scope TEXT NOT NULL,
    last_revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_authority_event_consumer_cursors_updated
    ON authority_event_consumer_cursors (updated_at DESC);

CREATE TABLE IF NOT EXISTS authority_event_consumer_failures (
    consumer_name TEXT NOT NULL,
    event_id UUID NOT NULL REFERENCES authority_events(event_id) ON DELETE CASCADE,
    failure_status TEXT NOT NULL,
    failure_message TEXT,
    failure_fingerprint TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT chk_authority_event_consumer_failures_status
        CHECK (failure_status IN ('RETRY', 'QUARANTINED'))
);
CREATE INDEX IF NOT EXISTS idx_authority_event_consumer_failures_retry
    ON authority_event_consumer_failures (next_attempt_at, updated_at)
    WHERE failure_status = 'RETRY';
CREATE INDEX IF NOT EXISTS idx_authority_event_consumer_failures_fingerprint
    ON authority_event_consumer_failures (failure_fingerprint, updated_at DESC);

CREATE TABLE IF NOT EXISTS authority_projection_checkpoints (
    projection_name TEXT NOT NULL,
    event_id UUID NOT NULL REFERENCES authority_events(event_id) ON DELETE CASCADE,
    event_created_at TIMESTAMPTZ NOT NULL,
    aggregate_scope TEXT NOT NULL,
    revision BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    projection_version TEXT NOT NULL,
    input_fingerprint TEXT NOT NULL,
    output_fingerprint TEXT NOT NULL,
    replay_batch_id UUID,
    manifest_fingerprint TEXT,
    manifest_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (projection_name, event_id)
);
CREATE INDEX IF NOT EXISTS idx_authority_projection_checkpoints_projection_time
    ON authority_projection_checkpoints (projection_name, event_created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_projection_checkpoints_scope_revision
    ON authority_projection_checkpoints (aggregate_scope, revision);
CREATE INDEX IF NOT EXISTS idx_authority_projection_checkpoints_replay_batch
    ON authority_projection_checkpoints (replay_batch_id, created_at DESC)
    WHERE replay_batch_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_projection_checkpoints_manifest
    ON authority_projection_checkpoints (projection_name, manifest_fingerprint);

CREATE TABLE IF NOT EXISTS authority_projection_heads (
    projection_name TEXT PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES authority_events(event_id) ON DELETE CASCADE,
    event_created_at TIMESTAMPTZ NOT NULL,
    aggregate_scope TEXT NOT NULL,
    revision BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    projection_version TEXT NOT NULL,
    input_fingerprint TEXT NOT NULL,
    output_fingerprint TEXT NOT NULL,
    replay_batch_id UUID,
    manifest_fingerprint TEXT,
    manifest_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_authority_projection_heads_scope_revision
    ON authority_projection_heads (aggregate_scope, revision);
CREATE INDEX IF NOT EXISTS idx_authority_projection_heads_manifest
    ON authority_projection_heads (manifest_fingerprint);

CREATE TABLE IF NOT EXISTS authority_projection_replay_runs (
    replay_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    projection_name TEXT NOT NULL,
    replay_mode TEXT NOT NULL DEFAULT 'DRY_RUN',
    reason TEXT,
    status TEXT NOT NULL,
    requested_limit INTEGER NOT NULL,
    scanned_events INTEGER NOT NULL DEFAULT 0,
    verified_events INTEGER NOT NULL DEFAULT 0,
    skipped_events INTEGER NOT NULL DEFAULT 0,
    missing_checkpoints INTEGER NOT NULL DEFAULT 0,
    mismatched_checkpoints INTEGER NOT NULL DEFAULT 0,
    replay_failures INTEGER NOT NULL DEFAULT 0,
    start_event_id UUID REFERENCES authority_events(event_id) ON DELETE SET NULL,
    end_event_id UUID REFERENCES authority_events(event_id) ON DELETE SET NULL,
    start_event_created_at TIMESTAMPTZ,
    end_event_created_at TIMESTAMPTZ,
    schema_contract_version INTEGER,
    schema_contract_fingerprint TEXT,
    projection_manifest_fingerprint TEXT,
    replay_source TEXT NOT NULL DEFAULT 'AUTHORITY_EVENT_LOG',
    event_range_fingerprint TEXT,
    verification_fingerprint TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_authority_projection_replay_runs_mode
        CHECK (replay_mode IN ('DRY_RUN')),
    CONSTRAINT chk_authority_projection_replay_runs_status
        CHECK (status IN ('RUNNING', 'VERIFIED', 'GAPS_FOUND', 'MISMATCH_FOUND', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_runs_projection_time
    ON authority_projection_replay_runs (projection_name, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_runs_status
    ON authority_projection_replay_runs (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_runs_contract
    ON authority_projection_replay_runs (schema_contract_fingerprint, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_runs_manifest
    ON authority_projection_replay_runs (projection_manifest_fingerprint, created_at DESC)
    WHERE projection_manifest_fingerprint IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_runs_verification
    ON authority_projection_replay_runs (verification_fingerprint)
    WHERE verification_fingerprint IS NOT NULL;

CREATE TABLE IF NOT EXISTS authority_projection_replay_run_events (
    replay_run_id UUID NOT NULL REFERENCES authority_projection_replay_runs(replay_run_id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES authority_events(event_id) ON DELETE CASCADE,
    event_created_at TIMESTAMPTZ NOT NULL,
    aggregate_scope TEXT NOT NULL,
    revision BIGINT NOT NULL,
    verdict TEXT NOT NULL,
    expected_input_fingerprint TEXT,
    actual_input_fingerprint TEXT NOT NULL,
    expected_output_fingerprint TEXT,
    actual_output_fingerprint TEXT,
    expected_manifest_fingerprint TEXT,
    actual_manifest_fingerprint TEXT,
    projection_version TEXT,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (replay_run_id, event_id),
    CONSTRAINT chk_authority_projection_replay_run_events_verdict
        CHECK (verdict IN (
            'VERIFIED',
            'SKIPPED_BY_MANIFEST',
            'MISSING_CHECKPOINT',
            'INPUT_MISMATCH',
            'OUTPUT_MISMATCH',
            'MANIFEST_MISMATCH',
            'REPLAY_FAILED'
        ))
);
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_run_events_verdict
    ON authority_projection_replay_run_events (verdict, created_at DESC);

CREATE TABLE IF NOT EXISTS authority_projection_manifests (
    projection_name TEXT PRIMARY KEY,
    projection_version TEXT NOT NULL,
    manifest_fingerprint TEXT NOT NULL,
    accepted_event_types TEXT[] NOT NULL,
    manifest_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_authority_projection_manifests_fingerprint
    ON authority_projection_manifests (manifest_fingerprint);

CREATE TABLE IF NOT EXISTS authority_command_ingress_log (
    command_id UUID PRIMARY KEY,
    command_type TEXT NOT NULL,
    aggregate_scope TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    claimed_actor TEXT NOT NULL,
    verified_principal TEXT NOT NULL,
    command_domain TEXT NOT NULL,
    command_topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    writer_lane_count INTEGER NOT NULL,
    writer_lane INTEGER NOT NULL,
    writer_lane_key_fingerprint TEXT NOT NULL,
    writer_lane_fencing_scope TEXT NOT NULL,
    writer_claim_epoch BIGINT,
    writer_claim_id UUID,
    writer_claim_fingerprint TEXT,
    origin_node TEXT NOT NULL,
    authority_route TEXT NOT NULL,
    provider_kind TEXT NOT NULL,
    contract_version INTEGER NOT NULL,
    manifest_payload JSONB NOT NULL,
    command_payload JSONB NOT NULL,
    payload_hash TEXT NOT NULL,
    command_fingerprint TEXT NOT NULL,
    status TEXT NOT NULL,
    accepted BOOLEAN,
    rejection_reason TEXT,
    result_revision BIGINT,
    result_message TEXT,
    replay_eligibility TEXT NOT NULL DEFAULT 'REPLAYABLE',
    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    guard_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    guard_evidence_fingerprint TEXT NOT NULL,
    failure_message TEXT,
    replay_attempts INTEGER NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_authority_command_ingress_log_status
        CHECK (status IN ('RECEIVED', 'APPLIED', 'REJECTED', 'FAILED', 'QUARANTINED')),
    CONSTRAINT chk_authority_command_ingress_log_replay_eligibility
        CHECK (replay_eligibility IN ('REPLAYABLE', 'NOT_REPLAYABLE')),
    CONSTRAINT chk_authority_command_ingress_log_guard_evidence_fingerprint
        CHECK (guard_evidence_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_authority_command_ingress_log_writer_lane_bounds
        CHECK (writer_lane_count > 0 AND writer_lane >= 0 AND writer_lane < writer_lane_count),
    CONSTRAINT chk_authority_command_ingress_log_writer_lane_fingerprint
        CHECK (writer_lane_key_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_authority_command_ingress_log_writer_lane_scope
        CHECK (length(trim(writer_lane_fencing_scope)) > 0),
    CONSTRAINT chk_authority_command_ingress_log_writer_claim_receipt
        CHECK (
            (
                writer_claim_epoch IS NULL
                AND writer_claim_id IS NULL
                AND writer_claim_fingerprint IS NULL
            )
            OR (
                writer_claim_epoch > 0
                AND writer_claim_id IS NOT NULL
                AND writer_claim_fingerprint ~ '^[0-9a-f]{64}$'
            )
        )
);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_status
    ON authority_command_ingress_log (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_scope_time
    ON authority_command_ingress_log (aggregate_scope, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_origin_time
    ON authority_command_ingress_log (origin_node, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_fingerprint
    ON authority_command_ingress_log (command_fingerprint);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_principal_time
    ON authority_command_ingress_log (verified_principal, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_route_time
    ON authority_command_ingress_log (command_domain, partition_key, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_replay
    ON authority_command_ingress_log (replay_eligibility, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_guard_evidence
    ON authority_command_ingress_log (guard_evidence_fingerprint);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_writer_lane_replay
    ON authority_command_ingress_log (command_domain, writer_lane, replay_eligibility, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_writer_lane_time
    ON authority_command_ingress_log (command_domain, writer_lane, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_writer_claim_id
    ON authority_command_ingress_log (writer_claim_id)
    WHERE writer_claim_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_writer_claim_fingerprint
    ON authority_command_ingress_log (writer_claim_fingerprint)
    WHERE writer_claim_fingerprint IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_writer_claim_epoch
    ON authority_command_ingress_log (command_domain, writer_claim_epoch, completed_at DESC)
    WHERE writer_claim_epoch IS NOT NULL;

CREATE TABLE IF NOT EXISTS authority_command_refusal_log (
    refusal_id UUID PRIMARY KEY,
    command_id UUID NOT NULL,
    command_type TEXT NOT NULL,
    aggregate_scope TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    claimed_actor TEXT NOT NULL,
    verified_principal TEXT NOT NULL,
    origin_node TEXT NOT NULL,
    authority_route TEXT NOT NULL,
    provider_kind TEXT NOT NULL,
    contract_version INTEGER NOT NULL,
    expected_contract_fingerprint TEXT NOT NULL,
    received_contract_fingerprint TEXT NOT NULL,
    rejection_reason TEXT NOT NULL,
    result_revision BIGINT NOT NULL,
    result_message TEXT NOT NULL DEFAULT '',
    replay_eligibility TEXT NOT NULL DEFAULT 'NOT_REPLAYABLE',
    manifest_payload JSONB NOT NULL,
    command_payload JSONB NOT NULL,
    result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload_hash TEXT NOT NULL,
    refusal_fingerprint TEXT NOT NULL,
    guard_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    guard_evidence_fingerprint TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_authority_command_refusal_log_rejection_reason
        CHECK (rejection_reason IN (
            'NONE',
            'STALE_FENCING_TOKEN',
            'STALE_REVISION',
            'EXPIRED_DEADLINE',
            'INVALID_ACTOR',
            'INVALID_SCOPE',
            'IDEMPOTENCY_CONFLICT',
            'STORE_UNAVAILABLE',
            'VALIDATION_FAILED'
        )),
    CONSTRAINT chk_authority_command_refusal_log_replay
        CHECK (replay_eligibility = 'NOT_REPLAYABLE'),
    CONSTRAINT chk_authority_command_refusal_log_guard_evidence_fingerprint
        CHECK (guard_evidence_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS idx_authority_command_refusal_log_command
    ON authority_command_refusal_log (command_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_refusal_log_scope_time
    ON authority_command_refusal_log (aggregate_scope, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_refusal_log_principal_time
    ON authority_command_refusal_log (verified_principal, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_refusal_log_reason_time
    ON authority_command_refusal_log (rejection_reason, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_refusal_log_fingerprint
    ON authority_command_refusal_log (refusal_fingerprint);
CREATE INDEX IF NOT EXISTS idx_authority_command_refusal_log_guard_evidence
    ON authority_command_refusal_log (guard_evidence_fingerprint);

CREATE TABLE IF NOT EXISTS authority_state_changelog (
    changelog_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES authority_events(event_id) ON DELETE CASCADE,
    command_id UUID NOT NULL REFERENCES authority_commands(command_id) ON DELETE CASCADE,
    aggregate_scope TEXT NOT NULL,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    revision BIGINT NOT NULL,
    command_domain TEXT NOT NULL,
    state_topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    state_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    state_fingerprint TEXT NOT NULL,
    event_fingerprint TEXT NOT NULL,
    event_chain_hash TEXT NOT NULL,
    event_created_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_authority_state_changelog_event UNIQUE (event_id),
    CONSTRAINT uq_authority_state_changelog_scope_revision UNIQUE (aggregate_scope, revision)
);
CREATE INDEX IF NOT EXISTS idx_authority_state_changelog_scope_latest
    ON authority_state_changelog (aggregate_scope, revision DESC);
CREATE INDEX IF NOT EXISTS idx_authority_state_changelog_route_revision
    ON authority_state_changelog (command_domain, partition_key, revision DESC);
CREATE INDEX IF NOT EXISTS idx_authority_state_changelog_topic_time
    ON authority_state_changelog (state_topic, event_created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_state_changelog_chain_hash
    ON authority_state_changelog (event_chain_hash);

CREATE TABLE IF NOT EXISTS authority_state_restore_runs (
    restore_run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_scope TEXT NOT NULL,
    restore_mode TEXT NOT NULL,
    reason TEXT,
    status TEXT NOT NULL,
    source_changelog_id UUID REFERENCES authority_state_changelog(changelog_id) ON DELETE SET NULL,
    source_event_id UUID REFERENCES authority_events(event_id) ON DELETE SET NULL,
    source_revision BIGINT,
    snapshot_revision BIGINT,
    restored BOOLEAN NOT NULL DEFAULT FALSE,
    message TEXT,
    schema_contract_version INTEGER,
    schema_contract_fingerprint TEXT,
    restore_source TEXT,
    source_state_fingerprint TEXT,
    snapshot_state_fingerprint TEXT,
    source_event_chain_hash TEXT,
    verification_fingerprint TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_authority_state_restore_runs_mode
        CHECK (restore_mode IN ('VERIFY', 'RESTORE')),
    CONSTRAINT chk_authority_state_restore_runs_status
        CHECK (status IN ('RUNNING', 'SOURCE_MISSING', 'SNAPSHOT_MISSING', 'MISMATCH_FOUND', 'VERIFIED', 'RESTORED', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_authority_state_restore_runs_scope_time
    ON authority_state_restore_runs (aggregate_scope, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_state_restore_runs_status
    ON authority_state_restore_runs (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_state_restore_runs_contract
    ON authority_state_restore_runs (schema_contract_fingerprint, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_state_restore_runs_verification
    ON authority_state_restore_runs (verification_fingerprint);

CREATE TABLE IF NOT EXISTS authority_lifecycle_policies (
    table_name TEXT PRIMARY KEY,
    lifecycle_timestamp_column TEXT NOT NULL,
    lifecycle_class TEXT NOT NULL,
    partition_strategy TEXT NOT NULL,
    partition_interval TEXT NOT NULL,
    retention_days INTEGER NOT NULL,
    archive_before_delete BOOLEAN NOT NULL DEFAULT TRUE,
    protect_incomplete_statuses TEXT[] NOT NULL DEFAULT '{}'::text[],
    retention_owner TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_authority_lifecycle_class
        CHECK (lifecycle_class IN ('APPEND_AUDIT', 'APPEND_EVENT', 'APPEND_OPERATION', 'APPEND_ANALYTICS', 'RESTORE_SOURCE')),
    CONSTRAINT chk_authority_lifecycle_partition_strategy
        CHECK (partition_strategy IN ('MONTHLY_RANGE', 'COMPACTED_CHANGELOG')),
    CONSTRAINT chk_authority_lifecycle_retention_days
        CHECK (retention_days > 0)
);

CREATE TABLE IF NOT EXISTS authority_partition_epochs (
    command_domain TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    command_topic TEXT NOT NULL,
    owner_node TEXT NOT NULL,
    epoch BIGINT NOT NULL,
    last_claim_id UUID,
    last_claim_fingerprint TEXT,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (command_domain, partition_key),
    CONSTRAINT chk_authority_partition_epochs_epoch
        CHECK (epoch > 0),
    CONSTRAINT chk_authority_partition_epochs_domain
        CHECK (command_domain <> ''),
    CONSTRAINT chk_authority_partition_epochs_partition_key
        CHECK (partition_key <> ''),
    CONSTRAINT chk_authority_partition_epochs_owner
        CHECK (owner_node <> ''),
    CONSTRAINT chk_authority_partition_epochs_last_claim_fingerprint
        CHECK (last_claim_fingerprint IS NULL OR last_claim_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS idx_authority_partition_epochs_owner
    ON authority_partition_epochs (owner_node, updated_at DESC);

CREATE TABLE IF NOT EXISTS authority_writer_claims (
    claim_id UUID PRIMARY KEY,
    command_domain TEXT NOT NULL,
    command_topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    owner_node TEXT NOT NULL,
    epoch BIGINT NOT NULL,
    previous_owner_node TEXT,
    previous_epoch BIGINT NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_fingerprint TEXT NOT NULL,
    CONSTRAINT fk_authority_writer_claims_partition
        FOREIGN KEY (command_domain, partition_key)
        REFERENCES authority_partition_epochs (command_domain, partition_key)
        ON DELETE CASCADE,
    CONSTRAINT chk_authority_writer_claims_domain
        CHECK (command_domain <> ''),
    CONSTRAINT chk_authority_writer_claims_command_topic
        CHECK (command_topic <> ''),
    CONSTRAINT chk_authority_writer_claims_partition_key
        CHECK (partition_key <> ''),
    CONSTRAINT chk_authority_writer_claims_owner
        CHECK (owner_node <> ''),
    CONSTRAINT chk_authority_writer_claims_epoch
        CHECK (epoch > 0),
    CONSTRAINT chk_authority_writer_claims_previous_epoch
        CHECK (previous_epoch >= 0),
    CONSTRAINT chk_authority_writer_claims_fingerprint
        CHECK (claim_fingerprint ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_authority_writer_claims_fingerprint
    ON authority_writer_claims (claim_fingerprint);
CREATE INDEX IF NOT EXISTS idx_authority_writer_claims_partition_epoch
    ON authority_writer_claims (command_domain, partition_key, epoch, claimed_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_writer_claims_owner_time
    ON authority_writer_claims (owner_node, claimed_at DESC);

CREATE TABLE IF NOT EXISTS authority_command_consumer_cursors (
    command_domain TEXT NOT NULL,
    command_partition INTEGER NOT NULL,
    command_topic TEXT NOT NULL,
    committed_offset BIGINT NOT NULL DEFAULT -1,
    partition_key TEXT NOT NULL,
    last_command_id UUID NOT NULL,
    last_result_revision BIGINT NOT NULL,
    last_result_accepted BOOLEAN NOT NULL,
    last_rejection_reason TEXT NOT NULL,
    writer_claim_id UUID NOT NULL,
    writer_claim_epoch BIGINT NOT NULL,
    writer_claim_fingerprint TEXT NOT NULL,
    owner_node TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (command_domain, command_partition),
    CONSTRAINT chk_authority_command_consumer_cursors_domain
        CHECK (command_domain <> ''),
    CONSTRAINT chk_authority_command_consumer_cursors_partition
        CHECK (command_partition >= 0),
    CONSTRAINT chk_authority_command_consumer_cursors_command_topic
        CHECK (command_topic <> ''),
    CONSTRAINT chk_authority_command_consumer_cursors_offset
        CHECK (committed_offset >= -1),
    CONSTRAINT chk_authority_command_consumer_cursors_partition_key
        CHECK (partition_key <> ''),
    CONSTRAINT chk_authority_command_consumer_cursors_revision
        CHECK (last_result_revision >= 0),
    CONSTRAINT chk_authority_command_consumer_cursors_writer_epoch
        CHECK (writer_claim_epoch > 0),
    CONSTRAINT chk_authority_command_consumer_cursors_claim_fingerprint
        CHECK (writer_claim_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_authority_command_consumer_cursors_owner
        CHECK (owner_node <> '')
);
CREATE INDEX IF NOT EXISTS idx_authority_command_consumer_cursors_owner
    ON authority_command_consumer_cursors (owner_node, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_command_consumer_cursors_claim
    ON authority_command_consumer_cursors (writer_claim_id);

CREATE TABLE IF NOT EXISTS authority_state_projection_cursors (
    projection_name TEXT NOT NULL,
    projection_version TEXT NOT NULL,
    command_domain TEXT NOT NULL,
    state_topic TEXT NOT NULL,
    state_partition INTEGER NOT NULL,
    committed_offset BIGINT NOT NULL DEFAULT -1,
    partition_key TEXT NOT NULL,
    last_command_id UUID NOT NULL,
    last_event_id UUID NOT NULL,
    last_revision BIGINT NOT NULL,
    last_restore_applied BOOLEAN NOT NULL,
    last_restore_message TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (projection_name, projection_version, command_domain, state_partition),
    CONSTRAINT chk_authority_state_projection_cursors_projection
        CHECK (projection_name <> ''),
    CONSTRAINT chk_authority_state_projection_cursors_version
        CHECK (projection_version <> ''),
    CONSTRAINT chk_authority_state_projection_cursors_domain
        CHECK (command_domain <> ''),
    CONSTRAINT chk_authority_state_projection_cursors_topic
        CHECK (state_topic <> ''),
    CONSTRAINT chk_authority_state_projection_cursors_partition
        CHECK (state_partition >= 0),
    CONSTRAINT chk_authority_state_projection_cursors_offset
        CHECK (committed_offset >= -1),
    CONSTRAINT chk_authority_state_projection_cursors_partition_key
        CHECK (partition_key <> ''),
    CONSTRAINT chk_authority_state_projection_cursors_revision
        CHECK (last_revision > 0),
    CONSTRAINT chk_authority_state_projection_cursors_message
        CHECK (last_restore_message <> '')
);
CREATE INDEX IF NOT EXISTS idx_authority_state_projection_cursors_projection_time
    ON authority_state_projection_cursors (projection_name, projection_version, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_authority_state_projection_cursors_topic_partition
    ON authority_state_projection_cursors (state_topic, state_partition, committed_offset DESC);

INSERT INTO authority_lifecycle_policies (
    table_name,
    lifecycle_timestamp_column,
    lifecycle_class,
    partition_strategy,
    partition_interval,
    retention_days,
    archive_before_delete,
    protect_incomplete_statuses,
    retention_owner,
    notes
) VALUES
    ('authority_commands', 'created_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY[]::text[], 'authority', 'Command audit and durable idempotency backstop.'),
    ('authority_command_ingress_log', 'received_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY['RECEIVED', 'FAILED']::text[], 'authority', 'Ingress frames, replayable terminal outcomes, and quarantined replay refusals.'),
    ('authority_command_refusal_log', 'created_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY[]::text[], 'authority', 'Pre-submit command refusal evidence for transport, contract, and malformed command rejections.'),
    ('authority_events', 'created_at', 'APPEND_EVENT', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY['PENDING']::text[], 'authority', 'Postgres event ledger mirror; durable log remains the replay source.'),
    ('authority_event_consumer_failures', 'created_at', 'APPEND_OPERATION', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY['RETRY', 'QUARANTINED']::text[], 'authority', 'Dispatcher retry and quarantine evidence.'),
    ('authority_projection_checkpoints', 'event_created_at', 'APPEND_OPERATION', 'MONTHLY_RANGE', 'P1M', 180, TRUE, ARRAY[]::text[], 'authority', 'Projection receipt history for replay verification.'),
    ('authority_projection_replay_runs', 'created_at', 'APPEND_OPERATION', 'MONTHLY_RANGE', 'P1M', 180, TRUE, ARRAY['RUNNING']::text[], 'authority', 'Projection replay drill summaries.'),
    ('authority_projection_replay_run_events', 'event_created_at', 'APPEND_OPERATION', 'MONTHLY_RANGE', 'P1M', 180, TRUE, ARRAY[]::text[], 'authority', 'Per-event replay drill evidence.'),
    ('authority_state_changelog', 'event_created_at', 'RESTORE_SOURCE', 'COMPACTED_CHANGELOG', 'P1M', 3650, TRUE, ARRAY[]::text[], 'authority', 'Current-state rebuild source until external compacted state topics own retention.'),
    ('authority_state_restore_runs', 'created_at', 'APPEND_OPERATION', 'MONTHLY_RANGE', 'P1M', 180, TRUE, ARRAY['RUNNING']::text[], 'authority', 'State restore drill evidence.'),
    ('authority_idempotency_conflicts', 'created_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY[]::text[], 'authority', 'Rejected idempotency conflict evidence.'),
    ('authority_writer_claims', 'claimed_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY[]::text[], 'authority', 'Append-only writer ownership receipts for partition fencing epochs.'),
    ('player_rank_audit', 'created_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 365, TRUE, ARRAY[]::text[], 'rank', 'Compliance-relevant rank change history.'),
    ('player_sessions', 'started_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY[]::text[], 'player', 'Session history; live presence moves to the hot state path.'),
    ('match_records', 'created_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 180, TRUE, ARRAY[]::text[], 'match', 'Match history header rows.'),
    ('match_participant_stats', 'created_at', 'APPEND_AUDIT', 'MONTHLY_RANGE', 'P1M', 180, TRUE, ARRAY[]::text[], 'match', 'Per-player match statistics.'),
    ('analytics_events', 'created_at', 'APPEND_ANALYTICS', 'MONTHLY_RANGE', 'P1M', 90, TRUE, ARRAY[]::text[], 'analytics', 'Analytics event sink staging.')
ON CONFLICT (table_name) DO UPDATE SET
    lifecycle_timestamp_column = EXCLUDED.lifecycle_timestamp_column,
    lifecycle_class = EXCLUDED.lifecycle_class,
    partition_strategy = EXCLUDED.partition_strategy,
    partition_interval = EXCLUDED.partition_interval,
    retention_days = EXCLUDED.retention_days,
    archive_before_delete = EXCLUDED.archive_before_delete,
    protect_incomplete_statuses = EXCLUDED.protect_incomplete_statuses,
    retention_owner = EXCLUDED.retention_owner,
    notes = EXCLUDED.notes,
    updated_at = CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_authority_lifecycle_policies_owner
    ON authority_lifecycle_policies (retention_owner, table_name);
CREATE INDEX IF NOT EXISTS idx_authority_commands_created_at_brin
    ON authority_commands USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_authority_command_ingress_log_received_at_brin
    ON authority_command_ingress_log USING BRIN (received_at);
CREATE INDEX IF NOT EXISTS idx_authority_command_refusal_log_created_at_brin
    ON authority_command_refusal_log USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_authority_events_created_at_brin
    ON authority_events USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_authority_event_consumer_failures_created_at_brin
    ON authority_event_consumer_failures USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_authority_projection_checkpoints_event_created_at_brin
    ON authority_projection_checkpoints USING BRIN (event_created_at);
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_runs_created_at_brin
    ON authority_projection_replay_runs USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_authority_projection_replay_run_events_event_created_at_brin
    ON authority_projection_replay_run_events USING BRIN (event_created_at);
CREATE INDEX IF NOT EXISTS idx_authority_state_changelog_event_created_at_brin
    ON authority_state_changelog USING BRIN (event_created_at);
CREATE INDEX IF NOT EXISTS idx_authority_state_restore_runs_created_at_brin
    ON authority_state_restore_runs USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_authority_idempotency_conflicts_created_at_brin
    ON authority_idempotency_conflicts USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_authority_writer_claims_claimed_at_brin
    ON authority_writer_claims USING BRIN (claimed_at);
CREATE INDEX IF NOT EXISTS idx_player_rank_audit_created_at_brin
    ON player_rank_audit USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_player_sessions_started_at_brin
    ON player_sessions USING BRIN (started_at);
CREATE INDEX IF NOT EXISTS idx_match_records_created_at_brin
    ON match_records USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_match_participant_stats_created_at_brin
    ON match_participant_stats USING BRIN (created_at);
CREATE INDEX IF NOT EXISTS idx_analytics_events_created_at_brin
    ON analytics_events USING BRIN (created_at);
