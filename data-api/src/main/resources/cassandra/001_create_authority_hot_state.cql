-- Canonical Cassandra schema for Fulcrum authority hot-state projections.
-- Apply this resource to the configured authority keyspace before registry startup.

CREATE TABLE IF NOT EXISTS authority_player_profiles_by_player (
    player_id uuid PRIMARY KEY,
    aggregate_scope text,
    aggregate_type text,
    aggregate_id text,
    revision bigint,
    command_domain text,
    state_topic text,
    partition_key text,
    source_partition int,
    source_offset bigint,
    source_command_id uuid,
    source_event_id uuid,
    state_payload text,
    state_fingerprint text,
    event_chain_hash text,
    event_created_at timestamp,
    projection_version text,
    updated_at timestamp
) WITH default_time_to_live = 900
   AND compaction = {
       'class': 'TimeWindowCompactionStrategy',
       'compaction_window_unit': 'MINUTES',
       'compaction_window_size': '15'
   };

CREATE TABLE IF NOT EXISTS authority_player_ranks_by_player (
    player_id uuid PRIMARY KEY,
    aggregate_scope text,
    aggregate_type text,
    aggregate_id text,
    revision bigint,
    command_domain text,
    state_topic text,
    partition_key text,
    source_partition int,
    source_offset bigint,
    source_command_id uuid,
    source_event_id uuid,
    state_payload text,
    state_fingerprint text,
    event_chain_hash text,
    event_created_at timestamp,
    projection_version text,
    updated_at timestamp
) WITH compaction = {
       'class': 'LeveledCompactionStrategy'
   };

CREATE TABLE IF NOT EXISTS authority_live_matches_by_match (
    match_id uuid PRIMARY KEY,
    aggregate_scope text,
    aggregate_type text,
    aggregate_id text,
    revision bigint,
    command_domain text,
    state_topic text,
    partition_key text,
    source_partition int,
    source_offset bigint,
    source_command_id uuid,
    source_event_id uuid,
    state_payload text,
    state_fingerprint text,
    event_chain_hash text,
    event_created_at timestamp,
    projection_version text,
    updated_at timestamp
) WITH default_time_to_live = 86400
   AND compaction = {
       'class': 'TimeWindowCompactionStrategy',
       'compaction_window_unit': 'HOURS',
       'compaction_window_size': '1'
   };
