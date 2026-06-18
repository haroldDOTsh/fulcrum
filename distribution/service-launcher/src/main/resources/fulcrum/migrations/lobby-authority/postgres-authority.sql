-- Generated migration artifact for the lobby authority-service record and decision stores.
-- Services must not execute schema creation at startup; the Kubernetes provisioning Job applies this file.
CREATE TABLE IF NOT EXISTS authority_records (
    aggregate_id TEXT PRIMARY KEY,
    revision BIGINT NOT NULL,
    fencing_epoch BIGINT NOT NULL,
    state_payload TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS authority_decisions (
    command_id TEXT PRIMARY KEY,
    aggregate_id TEXT NOT NULL,
    source_topic TEXT NOT NULL,
    source_partition INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    status TEXT NOT NULL,
    rejection_reason TEXT NOT NULL,
    revision BIGINT NOT NULL,
    replayed BOOLEAN NOT NULL,
    trace_id TEXT NOT NULL,
    decision_payload TEXT NOT NULL
);
