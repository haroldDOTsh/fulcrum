CREATE TABLE IF NOT EXISTS social_friend_edges
(
    owner_uuid
    UUID
    NOT
    NULL,
    peer_uuid
    UUID
    NOT
    NULL,
    state
    SMALLINT
    NOT
    NULL,
    relation_version
    BIGINT
    NOT
    NULL
    DEFAULT
    1,
    metadata
    JSONB
    NOT
    NULL
    DEFAULT
    '{}'
    :
    :
    jsonb,
    updated_at
    TIMESTAMPTZ
    NOT
    NULL
    DEFAULT
    NOW
(
),
    PRIMARY KEY
(
    owner_uuid,
    peer_uuid
),
    CONSTRAINT social_friend_edges_no_self CHECK
(
    owner_uuid
    <>
    peer_uuid
)
    );

CREATE INDEX IF NOT EXISTS social_friend_edges_undirected_idx
    ON social_friend_edges (LEAST(owner_uuid, peer_uuid), GREATEST(owner_uuid, peer_uuid));

CREATE INDEX IF NOT EXISTS social_friend_edges_accepted_idx
    ON social_friend_edges (owner_uuid)
    WHERE state = 2;
