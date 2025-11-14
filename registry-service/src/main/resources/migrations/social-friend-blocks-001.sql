CREATE TABLE IF NOT EXISTS social_friend_blocks
(
    owner_uuid
    UUID
    NOT
    NULL,
    peer_uuid
    UUID
    NOT
    NULL,
    scope
    SMALLINT
    NOT
    NULL,
    reason
    TEXT,
    created_at
    TIMESTAMPTZ
    NOT
    NULL
    DEFAULT
    NOW
(
),
    expires_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY
(
    owner_uuid,
    peer_uuid,
    scope
),
    CONSTRAINT social_friend_blocks_no_self CHECK
(
    owner_uuid
    <>
    peer_uuid
)
    );

CREATE INDEX IF NOT EXISTS social_friend_blocks_by_owner
    ON social_friend_blocks (owner_uuid);

CREATE INDEX IF NOT EXISTS social_friend_blocks_by_peer
    ON social_friend_blocks (peer_uuid);

CREATE INDEX IF NOT EXISTS social_friend_blocks_active_idx
    ON social_friend_blocks (owner_uuid, peer_uuid, scope)
    WHERE expires_at IS NULL OR expires_at > NOW();
