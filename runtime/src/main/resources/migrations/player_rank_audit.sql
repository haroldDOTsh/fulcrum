-- Fulcrum rank audit log schema
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS player_rank_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    player_uuid UUID NOT NULL,
    player_name VARCHAR(64),
    executor_type VARCHAR(32) NOT NULL,
    executor_name VARCHAR(64),
    executor_uuid UUID,
    main_rank_from VARCHAR(32),
    main_rank_to VARCHAR(32),
    all_ranks TEXT[] NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rank_audit_player ON player_rank_audit (player_uuid);
CREATE INDEX IF NOT EXISTS idx_rank_audit_created_at ON player_rank_audit (created_at);
