package sh.harold.fulcrum.api.data.authority.client;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Generated-client-shaped factories for authority command records.
 */
public final class AuthorityCommands {
    private static final long DEFAULT_DEADLINE_MILLIS = 5_000L;

    private final String actorId;

    private AuthorityCommands(String actorId) {
        this.actorId = requireText(actorId, "actorId");
    }

    public static AuthorityCommands actor(String actorId) {
        return new AuthorityCommands(actorId);
    }

    public PlayerCommands player(UUID playerId) {
        return new PlayerCommands(actorId, playerId);
    }

    public SessionCommands session(UUID playerId) {
        return new SessionCommands(actorId, playerId);
    }

    public RankCommands rank(UUID playerId) {
        return new RankCommands(actorId, playerId);
    }

    public MatchCommands match(UUID matchId) {
        return new MatchCommands(actorId, matchId);
    }

    public static final class PlayerCommands {
        private final String actorId;
        private final UUID playerId;

        private PlayerCommands(String actorId, UUID playerId) {
            this.actorId = actorId;
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        public DataAuthority.PlayerProfileCommand recordLogin(
            String username,
            long timestampEpochMillis,
            String currentServer,
            String currentProxy,
            String lastIp,
            String lastWorld,
            String lastLocation,
            String gameMode,
            Integer level,
            Float exp,
            Double health,
            Integer foodLevel
        ) {
            return profileCommand(
                DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
                username,
                timestampEpochMillis,
                currentServer,
                currentProxy,
                lastIp,
                lastWorld,
                lastLocation,
                gameMode,
                level,
                exp,
                health,
                foodLevel,
                null
            );
        }

        public DataAuthority.PlayerProfileCommand recordLogout(
            String username,
            long timestampEpochMillis,
            String currentServer,
            String currentProxy,
            String lastIp,
            String lastWorld,
            String lastLocation,
            String gameMode,
            String playtimeStartField
        ) {
            return profileCommand(
                DataAuthority.CommandType.RECORD_PLAYER_LOGOUT,
                username,
                timestampEpochMillis,
                currentServer,
                currentProxy,
                lastIp,
                lastWorld,
                lastLocation,
                gameMode,
                null,
                null,
                null,
                null,
                playtimeStartField
            );
        }

        private DataAuthority.PlayerProfileCommand profileCommand(
            DataAuthority.CommandType type,
            String username,
            long timestampEpochMillis,
            String currentServer,
            String currentProxy,
            String lastIp,
            String lastWorld,
            String lastLocation,
            String gameMode,
            Integer level,
            Float exp,
            Double health,
            Integer foodLevel,
            String playtimeStartField
        ) {
            return new DataAuthority.PlayerProfileCommand(
                manifest(
                    type,
                    actorId,
                    playerId,
                    timestampEpochMillis,
                    timestampEpochMillis + DEFAULT_DEADLINE_MILLIS,
                    DataAuthority.ANY_REVISION
                ),
                playerId,
                username,
                timestampEpochMillis,
                currentServer,
                currentProxy,
                lastIp,
                lastWorld,
                lastLocation,
                gameMode,
                level,
                exp,
                health,
                foodLevel,
                playtimeStartField
            );
        }
    }

    public static final class SessionCommands {
        private final String actorId;
        private final UUID playerId;

        private SessionCommands(String actorId, UUID playerId) {
            this.actorId = actorId;
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        public DataAuthority.PlayerSessionCommand startSession(
            String username,
            UUID sessionId,
            long timestampEpochMillis,
            String currentServer,
            String currentProxy,
            String lastIp,
            Integer protocolVersion
        ) {
            return sessionCommand(
                DataAuthority.CommandType.START_SESSION,
                username,
                sessionId,
                timestampEpochMillis,
                currentServer,
                currentProxy,
                lastIp,
                protocolVersion,
                null
            );
        }

        public DataAuthority.PlayerSessionCommand renewSession(
            String username,
            UUID sessionId,
            long timestampEpochMillis,
            String currentServer,
            String currentProxy,
            String lastIp,
            Integer protocolVersion
        ) {
            return sessionCommand(
                DataAuthority.CommandType.RENEW_SESSION,
                username,
                sessionId,
                timestampEpochMillis,
                currentServer,
                currentProxy,
                lastIp,
                protocolVersion,
                null
            );
        }

        public DataAuthority.PlayerSessionCommand endSession(
            String username,
            UUID sessionId,
            long timestampEpochMillis,
            String currentServer,
            String currentProxy,
            String lastIp,
            Integer protocolVersion,
            String disconnectReason
        ) {
            return sessionCommand(
                DataAuthority.CommandType.END_SESSION,
                username,
                sessionId,
                timestampEpochMillis,
                currentServer,
                currentProxy,
                lastIp,
                protocolVersion,
                disconnectReason
            );
        }

        private DataAuthority.PlayerSessionCommand sessionCommand(
            DataAuthority.CommandType type,
            String username,
            UUID sessionId,
            long timestampEpochMillis,
            String currentServer,
            String currentProxy,
            String lastIp,
            Integer protocolVersion,
            String disconnectReason
        ) {
            return new DataAuthority.PlayerSessionCommand(
                manifest(
                    type,
                    actorId,
                    playerId,
                    timestampEpochMillis,
                    timestampEpochMillis + DEFAULT_DEADLINE_MILLIS,
                    DataAuthority.ANY_REVISION
                ),
                playerId,
                username,
                sessionId,
                timestampEpochMillis,
                currentServer,
                currentProxy,
                lastIp,
                protocolVersion,
                disconnectReason
            );
        }
    }

    public static final class RankCommands {
        private final String actorId;
        private final UUID playerId;

        private RankCommands(String actorId, UUID playerId) {
            this.actorId = actorId;
            this.playerId = Objects.requireNonNull(playerId, "playerId");
        }

        public DataAuthority.PlayerRankCommand grantRank(
            String primaryRank,
            List<String> ranks,
            long expectedRevision,
            long timestampEpochMillis
        ) {
            return rankCommand(
                DataAuthority.CommandType.GRANT_RANK,
                primaryRank,
                ranks,
                expectedRevision,
                timestampEpochMillis
            );
        }

        public DataAuthority.PlayerRankCommand revokeRank(
            String primaryRank,
            List<String> ranks,
            long expectedRevision,
            long timestampEpochMillis
        ) {
            return rankCommand(
                DataAuthority.CommandType.REVOKE_RANK,
                primaryRank,
                ranks,
                expectedRevision,
                timestampEpochMillis
            );
        }

        private DataAuthority.PlayerRankCommand rankCommand(
            DataAuthority.CommandType type,
            String primaryRank,
            List<String> ranks,
            long expectedRevision,
            long timestampEpochMillis
        ) {
            return new DataAuthority.PlayerRankCommand(
                manifest(
                    type,
                    actorId,
                    playerId,
                    timestampEpochMillis,
                    timestampEpochMillis + DEFAULT_DEADLINE_MILLIS,
                    expectedRevision
                ),
                playerId,
                primaryRank,
                ranks
            );
        }
    }

    public static final class MatchCommands {
        private final String actorId;
        private final UUID matchId;

        private MatchCommands(String actorId, UUID matchId) {
            this.actorId = actorId;
            this.matchId = Objects.requireNonNull(matchId, "matchId");
        }

        public DataAuthority.MatchCommand recordStart(
            String familyId,
            String mapId,
            String serverId,
            String slotId,
            String state,
            Long startedAtEpochMillis,
            Map<String, Object> slotMetadata,
            List<DataAuthority.MatchParticipant> participants,
            long timestampEpochMillis
        ) {
            return matchCommand(
                DataAuthority.CommandType.RECORD_MATCH_START,
                familyId,
                mapId,
                serverId,
                slotId,
                state,
                startedAtEpochMillis,
                null,
                slotMetadata,
                participants,
                timestampEpochMillis
            );
        }

        public DataAuthority.MatchCommand recordEnd(
            String familyId,
            String mapId,
            String serverId,
            String slotId,
            String state,
            Long startedAtEpochMillis,
            Long endedAtEpochMillis,
            Map<String, Object> slotMetadata,
            List<DataAuthority.MatchParticipant> participants,
            long timestampEpochMillis
        ) {
            return matchCommand(
                DataAuthority.CommandType.RECORD_MATCH_END,
                familyId,
                mapId,
                serverId,
                slotId,
                state,
                startedAtEpochMillis,
                endedAtEpochMillis,
                slotMetadata,
                participants,
                timestampEpochMillis
            );
        }

        private DataAuthority.MatchCommand matchCommand(
            DataAuthority.CommandType type,
            String familyId,
            String mapId,
            String serverId,
            String slotId,
            String state,
            Long startedAtEpochMillis,
            Long endedAtEpochMillis,
            Map<String, Object> slotMetadata,
            List<DataAuthority.MatchParticipant> participants,
            long timestampEpochMillis
        ) {
            long idempotencyEpochMillis = matchIdempotencyEpochMillis(
                type,
                startedAtEpochMillis,
                endedAtEpochMillis,
                timestampEpochMillis
            );
            return new DataAuthority.MatchCommand(
                manifest(
                    type,
                    actorId,
                    matchId,
                    idempotencyEpochMillis,
                    timestampEpochMillis + DEFAULT_DEADLINE_MILLIS,
                    DataAuthority.ANY_REVISION
                ),
                matchId,
                familyId,
                mapId,
                serverId,
                slotId,
                state,
                startedAtEpochMillis,
                endedAtEpochMillis,
                slotMetadata,
                participants
            );
        }

        private static long matchIdempotencyEpochMillis(
            DataAuthority.CommandType type,
            Long startedAtEpochMillis,
            Long endedAtEpochMillis,
            long timestampEpochMillis
        ) {
            if (startedAtEpochMillis != null) {
                return startedAtEpochMillis;
            }
            if (type == DataAuthority.CommandType.RECORD_MATCH_END && endedAtEpochMillis != null) {
                return endedAtEpochMillis;
            }
            return timestampEpochMillis;
        }
    }

    private static DataAuthority.CommandManifest manifest(
        DataAuthority.CommandType type,
        String actorId,
        UUID aggregateId,
        long idempotencyEpochMillis,
        long deadlineEpochMillis,
        long expectedRevision
    ) {
        return DataAuthority.CommandManifest.create(
            UUID.randomUUID(),
            type,
            actorId,
            scope(type, aggregateId),
            type.name() + ":" + aggregateId + ":" + idempotencyEpochMillis,
            deadlineEpochMillis,
            "",
            expectedRevision
        );
    }

    private static String scope(DataAuthority.CommandType type, UUID aggregateId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(aggregateId, "aggregateId");
        return DataAuthorityCommandContracts.contract(type).aggregateScopePrefix() + aggregateId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
