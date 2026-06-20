CREATE KEYSPACE IF NOT EXISTS fulcrum
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS fulcrum.auction_escrow_projection (
    aggregate_id text PRIMARY KEY,
    auction_id text,
    status text,
    total_held_minor bigint,
    total_released_minor bigint,
    revision bigint
);
