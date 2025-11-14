package sh.harold.fulcrum.registry.social;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.schema.SchemaDefinition;
import sh.harold.fulcrum.api.data.schema.SchemaRegistry;
import sh.harold.fulcrum.api.friends.FriendBlockScope;
import sh.harold.fulcrum.api.friends.FriendMutationRequest;
import sh.harold.fulcrum.api.friends.FriendRelationState;
import sh.harold.fulcrum.api.friends.FriendSnapshot;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repository that applies friend graph mutations and assembles snapshots.
 */
public final class FriendGraphRepository {

    private final PostgresConnectionAdapter adapter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;

    public FriendGraphRepository(PostgresConnectionAdapter adapter, Logger logger) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void ensureSchema(ClassLoader classLoader) {
        SchemaRegistry.ensureSchema(
                adapter,
                SchemaDefinition.fromResource(
                        "social-friend-edges-001",
                        "Create social friend edge table",
                        classLoader,
                        "migrations/social-friend-edges-001.sql"
                )
        );
        SchemaRegistry.ensureSchema(
                adapter,
                SchemaDefinition.fromResource(
                        "social-friend-blocks-001",
                        "Create social friend block table",
                        classLoader,
                        "migrations/social-friend-blocks-001.sql"
                )
        );
    }

    public FriendSnapshot loadSnapshot(UUID playerId) throws SQLException {
        try (Connection connection = adapter.getConnection()) {
            connection.setAutoCommit(true);
            return loadSnapshot(connection, playerId);
        }
    }

    public FriendGraphMutationResult applyMutation(FriendMutationRequest request) throws SQLException {
        try (Connection connection = adapter.getConnection()) {
            connection.setAutoCommit(false);
            try {
                FriendGraphMutationResult result = switch (request.type()) {
                    case INVITE_SEND -> handleInviteSend(connection, request);
                    case INVITE_ACCEPT -> handleInviteAccept(connection, request);
                    case INVITE_DECLINE -> handleInviteDecline(connection, request);
                    case INVITE_CANCEL -> handleInviteCancel(connection, request);
                    case UNFRIEND -> handleUnfriend(connection, request);
                    case BLOCK -> handleBlock(connection, request);
                    case UNBLOCK -> handleUnblock(connection, request);
                    default ->
                            new FriendGraphMutationResult(false, request.type(), request.actorId(), request.targetId(), null, null, 0L, 0L, null, null, null, false, null, Instant.now(), "Unsupported mutation type");
                };
                if (result.success()) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    public List<BlockChange> purgeExpiredBlocks(Instant now) throws SQLException {
        String sql = """
                DELETE FROM social_friend_blocks
                WHERE expires_at IS NOT NULL AND expires_at <= ?
                RETURNING owner_uuid, peer_uuid, scope
                """;
        List<BlockChange> expired = new ArrayList<>();
        try (Connection connection = adapter.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    expired.add(new BlockChange(
                            (UUID) rs.getObject("owner_uuid"),
                            (UUID) rs.getObject("peer_uuid"),
                            FriendBlockScope.fromCode(rs.getInt("scope"))
                    ));
                }
            }
        }
        return expired;
    }

    private FriendGraphMutationResult handleInviteSend(Connection connection, FriendMutationRequest request) throws SQLException {
        if (request.actorId().equals(request.targetId())) {
            return failure(request, "Cannot send a friend request to yourself");
        }

        PairState state = loadPairState(connection, request.actorId(), request.targetId(), true);
        if (state.hasActiveBlockForInvite()) {
            return failure(request, "Cannot send request while blocked");
        }

        EdgeRecord actorEdge = state.actorEdge;
        EdgeRecord targetEdge = state.targetEdge;
        Instant now = Instant.now();

        boolean implicitAccept = actorEdge != null && actorEdge.state == FriendRelationState.INVITE_INCOMING
                && targetEdge != null && targetEdge.state == FriendRelationState.INVITE_OUTGOING;

        if (implicitAccept) {
            long targetRowVersion = storeEdge(connection, request.targetId(), request.actorId(), FriendRelationState.ACCEPTED, targetEdge.nextVersion(), request.metadata(), now);
            long actorRowVersion = storeEdge(connection, request.actorId(), request.targetId(), FriendRelationState.ACCEPTED, actorEdge.nextVersion(), request.metadata(), now);
            FriendSnapshot actorSnapshot = loadSnapshot(connection, request.actorId());
            FriendSnapshot targetSnapshot = loadSnapshot(connection, request.targetId());
            return new FriendGraphMutationResult(true, request.type(), request.actorId(), request.targetId(),
                    FriendRelationState.ACCEPTED, FriendRelationState.ACCEPTED,
                    actorRowVersion, targetRowVersion, actorSnapshot, targetSnapshot,
                    null, false, null, now, null);
        }

        if (actorEdge != null && actorEdge.state == FriendRelationState.INVITE_OUTGOING) {
            return failure(request, "Invite already pending");
        }

        long actorVersion = storeEdge(connection, request.actorId(), request.targetId(), FriendRelationState.INVITE_OUTGOING,
                actorEdge == null ? 1 : actorEdge.nextVersion(), request.metadata(), now);
        long targetVersion = storeEdge(connection, request.targetId(), request.actorId(), FriendRelationState.INVITE_INCOMING,
                targetEdge == null ? 1 : targetEdge.nextVersion(), request.metadata(), now);
        FriendSnapshot actorSnapshot = loadSnapshot(connection, request.actorId());
        FriendSnapshot targetSnapshot = loadSnapshot(connection, request.targetId());
        return new FriendGraphMutationResult(true, request.type(), request.actorId(), request.targetId(),
                FriendRelationState.INVITE_OUTGOING, FriendRelationState.INVITE_INCOMING,
                actorVersion, targetVersion, actorSnapshot, targetSnapshot,
                null, false, null, now, null);
    }

    private FriendGraphMutationResult handleInviteAccept(Connection connection, FriendMutationRequest request) throws SQLException {
        PairState state = loadPairState(connection, request.actorId(), request.targetId(), true);
        EdgeRecord actorEdge = state.actorEdge;
        EdgeRecord targetEdge = state.targetEdge;
        if (actorEdge == null || actorEdge.state != FriendRelationState.INVITE_INCOMING
                || targetEdge == null || targetEdge.state != FriendRelationState.INVITE_OUTGOING) {
            return failure(request, "No incoming invite to accept");
        }
        Instant now = Instant.now();
        long actorVersion = storeEdge(connection, request.actorId(), request.targetId(), FriendRelationState.ACCEPTED,
                actorEdge.nextVersion(), request.metadata(), now);
        long targetVersion = storeEdge(connection, request.targetId(), request.actorId(), FriendRelationState.ACCEPTED,
                targetEdge.nextVersion(), request.metadata(), now);
        FriendSnapshot actorSnapshot = loadSnapshot(connection, request.actorId());
        FriendSnapshot targetSnapshot = loadSnapshot(connection, request.targetId());
        return new FriendGraphMutationResult(true, request.type(), request.actorId(), request.targetId(),
                FriendRelationState.ACCEPTED, FriendRelationState.ACCEPTED,
                actorVersion, targetVersion, actorSnapshot, targetSnapshot,
                null, false, null, now, null);
    }

    private FriendGraphMutationResult handleInviteDecline(Connection connection, FriendMutationRequest request) throws SQLException {
        PairState state = loadPairState(connection, request.actorId(), request.targetId(), true);
        if (state.actorEdge == null && state.targetEdge == null) {
            return failure(request, "No pending invite to decline");
        }
        deleteEdge(connection, request.actorId(), request.targetId());
        deleteEdge(connection, request.targetId(), request.actorId());
        Instant now = Instant.now();
        FriendSnapshot actorSnapshot = loadSnapshot(connection, request.actorId());
        FriendSnapshot targetSnapshot = loadSnapshot(connection, request.targetId());
        return new FriendGraphMutationResult(true, request.type(), request.actorId(), request.targetId(),
                null, null, 0L, 0L, actorSnapshot, targetSnapshot,
                null, false, null, now, null);
    }

    private FriendGraphMutationResult handleInviteCancel(Connection connection, FriendMutationRequest request) throws SQLException {
        return handleInviteDecline(connection, request);
    }

    private FriendGraphMutationResult handleUnfriend(Connection connection, FriendMutationRequest request) throws SQLException {
        PairState state = loadPairState(connection, request.actorId(), request.targetId(), true);
        if ((state.actorEdge == null || state.actorEdge.state != FriendRelationState.ACCEPTED)
                && (state.targetEdge == null || state.targetEdge.state != FriendRelationState.ACCEPTED)) {
            return failure(request, "Players are not friends");
        }
        deleteEdge(connection, request.actorId(), request.targetId());
        deleteEdge(connection, request.targetId(), request.actorId());
        Instant now = Instant.now();
        FriendSnapshot actorSnapshot = loadSnapshot(connection, request.actorId());
        FriendSnapshot targetSnapshot = loadSnapshot(connection, request.targetId());
        return new FriendGraphMutationResult(true, request.type(), request.actorId(), request.targetId(),
                null, null, 0L, 0L, actorSnapshot, targetSnapshot,
                null, false, null, now, null);
    }

    private FriendGraphMutationResult handleBlock(Connection connection, FriendMutationRequest request) throws SQLException {
        FriendBlockScope scope = Objects.requireNonNullElse(request.scope(), FriendBlockScope.GLOBAL);
        // Remove relations regardless of state
        deleteEdge(connection, request.actorId(), request.targetId());
        deleteEdge(connection, request.targetId(), request.actorId());

        Instant now = Instant.now();
        upsertBlock(connection, request.actorId(), request.targetId(), scope, request.reason(), request.expiresAt(), request.metadata());

        FriendSnapshot actorSnapshot = loadSnapshot(connection, request.actorId());
        FriendSnapshot targetSnapshot = loadSnapshot(connection, request.targetId());
        return new FriendGraphMutationResult(true, request.type(), request.actorId(), request.targetId(),
                null, null, 0L, 0L, actorSnapshot, targetSnapshot,
                scope, true, request.expiresAt(), now, null);
    }

    private FriendGraphMutationResult handleUnblock(Connection connection, FriendMutationRequest request) throws SQLException {
        FriendBlockScope scope = Objects.requireNonNullElse(request.scope(), FriendBlockScope.GLOBAL);
        String sql = "DELETE FROM social_friend_blocks WHERE owner_uuid = ? AND peer_uuid = ? AND scope = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, request.actorId());
            statement.setObject(2, request.targetId());
            statement.setInt(3, scope.code());
            int removed = statement.executeUpdate();
            if (removed == 0) {
                return failure(request, "No block entry to remove");
            }
        }
        Instant now = Instant.now();
        FriendSnapshot actorSnapshot = loadSnapshot(connection, request.actorId());
        FriendSnapshot targetSnapshot = loadSnapshot(connection, request.targetId());
        return new FriendGraphMutationResult(true, request.type(), request.actorId(), request.targetId(),
                null, null, 0L, 0L, actorSnapshot, targetSnapshot,
                scope, false, null, now, null);
    }

    private PairState loadPairState(Connection connection,
                                    UUID actorId,
                                    UUID targetId,
                                    boolean forUpdate) throws SQLException {
        String edgeSql = """
                SELECT owner_uuid, peer_uuid, state, relation_version
                FROM social_friend_edges
                WHERE (owner_uuid = ? AND peer_uuid = ?)
                   OR (owner_uuid = ? AND peer_uuid = ?)
                ORDER BY owner_uuid, peer_uuid
                """ + (forUpdate ? " FOR UPDATE" : "");
        EdgeRecord actorEdge = null;
        EdgeRecord targetEdge = null;
        try (PreparedStatement statement = connection.prepareStatement(edgeSql)) {
            statement.setObject(1, actorId);
            statement.setObject(2, targetId);
            statement.setObject(3, targetId);
            statement.setObject(4, actorId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID owner = (UUID) rs.getObject("owner_uuid");
                    UUID peer = (UUID) rs.getObject("peer_uuid");
                    FriendRelationState stateVal = FriendRelationState.fromCode(rs.getShort("state"));
                    long version = rs.getLong("relation_version");
                    EdgeRecord record = new EdgeRecord(owner, peer, stateVal, version);
                    if (owner.equals(actorId)) {
                        actorEdge = record;
                    } else {
                        targetEdge = record;
                    }
                }
            }
        }

        String blockSql = """
                SELECT owner_uuid, peer_uuid, scope, expires_at
                FROM social_friend_blocks
                WHERE ((owner_uuid = ? AND peer_uuid = ?)
                    OR (owner_uuid = ? AND peer_uuid = ?))
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY owner_uuid, peer_uuid
                """ + (forUpdate ? " FOR UPDATE" : "");
        Map<FriendBlockScope, BlockRecord> actorBlocks = new EnumMap<>(FriendBlockScope.class);
        Map<FriendBlockScope, BlockRecord> targetBlocks = new EnumMap<>(FriendBlockScope.class);
        try (PreparedStatement statement = connection.prepareStatement(blockSql)) {
            statement.setObject(1, actorId);
            statement.setObject(2, targetId);
            statement.setObject(3, targetId);
            statement.setObject(4, actorId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID owner = (UUID) rs.getObject("owner_uuid");
                    UUID peer = (UUID) rs.getObject("peer_uuid");
                    FriendBlockScope scope = FriendBlockScope.fromCode(rs.getInt("scope"));
                    Timestamp expiresAt = rs.getTimestamp("expires_at");
                    BlockRecord record = new BlockRecord(owner, peer, scope, expiresAt != null ? expiresAt.toInstant() : null);
                    if (owner.equals(actorId)) {
                        actorBlocks.put(scope, record);
                    } else {
                        targetBlocks.put(scope, record);
                    }
                }
            }
        }
        return new PairState(actorEdge, targetEdge, actorBlocks, targetBlocks);
    }

    private FriendSnapshot loadSnapshot(Connection connection, UUID playerId) throws SQLException {
        String edgeSql = "SELECT peer_uuid, state, relation_version FROM social_friend_edges WHERE owner_uuid = ?";
        Set<UUID> friends = new HashSet<>();
        Set<UUID> outgoing = new HashSet<>();
        Set<UUID> incoming = new HashSet<>();
        long relationVersion = 0L;
        try (PreparedStatement statement = connection.prepareStatement(edgeSql)) {
            statement.setObject(1, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID peer = (UUID) rs.getObject("peer_uuid");
                    FriendRelationState state = FriendRelationState.fromCode(rs.getShort("state"));
                    long version = rs.getLong("relation_version");
                    relationVersion = Math.max(relationVersion, version);
                    switch (state) {
                        case ACCEPTED -> friends.add(peer);
                        case INVITE_OUTGOING -> outgoing.add(peer);
                        case INVITE_INCOMING -> incoming.add(peer);
                    }
                }
            }
        }

        Map<FriendBlockScope, Set<UUID>> blockedOut = new EnumMap<>(FriendBlockScope.class);
        Map<FriendBlockScope, Set<UUID>> blockedIn = new EnumMap<>(FriendBlockScope.class);
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            blockedOut.put(scope, new HashSet<>());
            blockedIn.put(scope, new HashSet<>());
        }

        String blockSql = """
                SELECT owner_uuid, peer_uuid, scope, created_at
                FROM social_friend_blocks
                WHERE (owner_uuid = ? OR peer_uuid = ?)
                  AND (expires_at IS NULL OR expires_at > NOW())
                """;
        long blockVersion = 0L;
        try (PreparedStatement statement = connection.prepareStatement(blockSql)) {
            statement.setObject(1, playerId);
            statement.setObject(2, playerId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID owner = (UUID) rs.getObject("owner_uuid");
                    UUID peer = (UUID) rs.getObject("peer_uuid");
                    FriendBlockScope scope = FriendBlockScope.fromCode(rs.getInt("scope"));
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    if (createdAt != null) {
                        blockVersion = Math.max(blockVersion, createdAt.toInstant().toEpochMilli());
                    }
                    if (owner.equals(playerId)) {
                        blockedOut.get(scope).add(peer);
                    } else if (peer.equals(playerId)) {
                        blockedIn.get(scope).add(owner);
                    }
                }
            }
        }

        long version = Math.max(relationVersion, blockVersion);
        return new FriendSnapshot(version, friends, outgoing, incoming, blockedOut, blockedIn);
    }

    private long storeEdge(Connection connection,
                           UUID owner,
                           UUID peer,
                           FriendRelationState state,
                           long relationVersion,
                           Map<String, Object> metadata,
                           Instant now) throws SQLException {
        String sql = """
                INSERT INTO social_friend_edges(owner_uuid, peer_uuid, state, relation_version, metadata, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (owner_uuid, peer_uuid)
                DO UPDATE SET state = EXCLUDED.state,
                              relation_version = EXCLUDED.relation_version,
                              metadata = EXCLUDED.metadata,
                              updated_at = EXCLUDED.updated_at
                RETURNING relation_version
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, owner);
            statement.setObject(2, peer);
            statement.setInt(3, state.code());
            statement.setLong(4, Math.max(1, relationVersion));
            statement.setString(5, serializeMetadata(metadata));
            statement.setTimestamp(6, Timestamp.from(now));
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return relationVersion;
    }

    private void deleteEdge(Connection connection, UUID owner, UUID peer) throws SQLException {
        String sql = "DELETE FROM social_friend_edges WHERE owner_uuid = ? AND peer_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, owner);
            statement.setObject(2, peer);
            statement.executeUpdate();
        }
    }

    private void upsertBlock(Connection connection,
                             UUID owner,
                             UUID peer,
                             FriendBlockScope scope,
                             String reason,
                             Instant expiresAt,
                             Map<String, Object> metadata) throws SQLException {
        String sql = """
                INSERT INTO social_friend_blocks(owner_uuid, peer_uuid, scope, reason, created_at, expires_at, metadata)
                VALUES (?, ?, ?, ?, NOW(), ?, ?::jsonb)
                ON CONFLICT (owner_uuid, peer_uuid, scope)
                DO UPDATE SET reason = EXCLUDED.reason,
                              expires_at = EXCLUDED.expires_at,
                              metadata = EXCLUDED.metadata
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, owner);
            statement.setObject(2, peer);
            statement.setInt(3, scope.code());
            statement.setString(4, reason);
            if (expiresAt != null) {
                statement.setTimestamp(5, Timestamp.from(expiresAt));
            } else {
                statement.setNull(5, Types.TIMESTAMP);
            }
            statement.setString(6, serializeMetadata(metadata));
            statement.executeUpdate();
        }
    }

    private FriendGraphMutationResult failure(FriendMutationRequest request, String reason) {
        return new FriendGraphMutationResult(false, request.type(), request.actorId(), request.targetId(),
                null, null, 0L, 0L, null, null, null, false, null, Instant.now(), reason);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            if (metadata == null || metadata.isEmpty()) {
                return "{}";
            }
            return mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            logger.log(Level.WARNING, "Failed to serialise friend metadata", e);
            return "{}";
        }
    }

    public record BlockChange(UUID ownerId, UUID peerId, FriendBlockScope scope) {
    }

    private record EdgeRecord(UUID ownerId, UUID peerId, FriendRelationState state, long relationVersion) {
        long nextVersion() {
            return relationVersion + 1;
        }
    }

    private record BlockRecord(UUID ownerId, UUID peerId, FriendBlockScope scope, Instant expiresAt) {
    }

    private record PairState(EdgeRecord actorEdge, EdgeRecord targetEdge,
                             Map<FriendBlockScope, BlockRecord> actorBlocks,
                             Map<FriendBlockScope, BlockRecord> targetBlocks) {

        boolean hasActiveBlockForInvite() {
                return !actorBlocks.isEmpty() || !targetBlocks.isEmpty();
            }
        }
}
