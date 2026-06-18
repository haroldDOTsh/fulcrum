-- Generated migration artifact for lobby authority hot projections.
-- Services must not execute schema creation at startup; the Kubernetes provisioning Job applies this file.
CREATE KEYSPACE IF NOT EXISTS fulcrum
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS fulcrum.presence_hot (
    subject_id text PRIMARY KEY,
    presence_id text,
    owner_instance_id text,
    owner_token text,
    owner_epoch bigint,
    lifecycle_status text,
    session_id text,
    route_id text,
    observed_at text,
    expires_at text,
    revision bigint
);

CREATE TABLE IF NOT EXISTS fulcrum.subject_identity_hot (
    identity_provider text,
    external_identity text,
    subject_id text,
    lifecycle_status text,
    registered_by text,
    registered_at text,
    retired_by text,
    retired_at text,
    retire_reason text,
    revision bigint,
    PRIMARY KEY ((identity_provider), external_identity)
);

CREATE TABLE IF NOT EXISTS fulcrum.route_hot (
    route_id text PRIMARY KEY,
    subject_id text,
    target_session_id text,
    target_instance_id text,
    lifecycle_status text,
    requested_at text,
    expires_at text,
    completed_at text,
    revision bigint
);

CREATE TABLE IF NOT EXISTS fulcrum.session_hot (
    session_id text PRIMARY KEY,
    experience_id text,
    slot_id text,
    owner_instance_id text,
    owner_token text,
    owner_epoch bigint,
    resolved_manifest_id text,
    lifecycle_status text,
    opened_at text,
    lease_expires_at text,
    activated_at text,
    closed_at text,
    close_reason text,
    revision bigint
);

CREATE TABLE IF NOT EXISTS fulcrum.artifact_metadata_hot (
    digest_algorithm text,
    digest_value text,
    kind text,
    byte_length bigint,
    content_address text,
    producer_principal text,
    provenance text,
    published_at text,
    revision bigint,
    PRIMARY KEY ((digest_algorithm), digest_value)
);

CREATE TABLE IF NOT EXISTS fulcrum.standard_player_profile_effective_hot (
    subject_id text PRIMARY KEY,
    display_name text,
    updated_by text,
    observed_at text,
    revision bigint
);

CREATE TABLE IF NOT EXISTS fulcrum.standard_rank_effective_hot (
    subject_id text PRIMARY KEY,
    primary_rank_key text,
    permissions text,
    updated_by text,
    updated_at text,
    revision bigint
);

CREATE TABLE IF NOT EXISTS fulcrum.standard_punishment_active_hot (
    subject_id text PRIMARY KEY,
    punishment_id text,
    reason text,
    issued_by text,
    issued_at text,
    expires_at text,
    revision bigint
);

CREATE TABLE IF NOT EXISTS fulcrum.standard_economy_balance_hot (
    subject_id text,
    currency_key text,
    balance_minor_units bigint,
    last_entry_id text,
    updated_by text,
    updated_at text,
    revision bigint,
    PRIMARY KEY ((subject_id), currency_key)
);

CREATE TABLE IF NOT EXISTS fulcrum.standard_stats_counter_hot (
    subject_id text,
    stat_key text,
    total bigint,
    last_entry_id text,
    updated_by text,
    updated_at text,
    revision bigint,
    PRIMARY KEY ((subject_id), stat_key)
);
