package sh.harold.fulcrum.api.data.authority.client;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Generated-client-shaped factories for authority command records.
 */
public final class AuthorityCommands {
    private static final long DEFAULT_DEADLINE_MILLIS = 5_000L;
    private static final String TRANSPORT_STAMPED_ACTOR = "node:transport-unverified";

    private final String actorId;

    private AuthorityCommands(String actorId) {
        this.actorId = requireText(actorId, "actorId");
    }

    public static AuthorityCommands actor(String actorId) {
        return new AuthorityCommands(actorId);
    }

    public static AuthorityCommands transport() {
        return new AuthorityCommands(TRANSPORT_STAMPED_ACTOR);
    }

    public PlayerCommands player(UUID playerId) {
        return player(DataAuthority.Subject.player(playerId));
    }

    public PlayerCommands player(DataAuthority.Subject subject) {
        return new PlayerCommands(actorId, subject);
    }

    public SessionCommands session(UUID playerId) {
        return session(DataAuthority.Subject.player(playerId));
    }

    public SessionCommands session(DataAuthority.Subject subject) {
        return new SessionCommands(actorId, subject);
    }

    public RankCommands rank(UUID playerId) {
        return new RankCommands(actorId, playerId);
    }

    public MatchCommands match(UUID matchId) {
        return new MatchCommands(actorId, matchId);
    }

    public static final class PlayerCommands {
        private final String actorId;
        private final DataAuthority.Subject subject;

        private PlayerCommands(String actorId, DataAuthority.Subject subject) {
            this.actorId = actorId;
            this.subject = Objects.requireNonNull(subject, "subject");
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
                "RECORD_PLAYER_LOGIN",
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
                "RECORD_PLAYER_LOGOUT",
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
            String declarationId,
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
                    declarationId,
                    actorId,
                    subject.subjectId(),
                    timestampEpochMillis,
                    timestampEpochMillis + DEFAULT_DEADLINE_MILLIS,
                    DataAuthority.ANY_REVISION
                ),
                subject.subjectId(),
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
        private final DataAuthority.Subject subject;

        private SessionCommands(String actorId, DataAuthority.Subject subject) {
            this.actorId = actorId;
            this.subject = Objects.requireNonNull(subject, "subject");
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
                "START_SESSION",
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
                "RENEW_SESSION",
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
                "END_SESSION",
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
            String declarationId,
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
                    declarationId,
                    actorId,
                    subject.subjectId(),
                    timestampEpochMillis,
                    timestampEpochMillis + DEFAULT_DEADLINE_MILLIS,
                    DataAuthority.ANY_REVISION
                ),
                subject.subjectId(),
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
                "GRANT_RANK",
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
                "REVOKE_RANK",
                primaryRank,
                ranks,
                expectedRevision,
                timestampEpochMillis
            );
        }

        private DataAuthority.PlayerRankCommand rankCommand(
            String declarationId,
            String primaryRank,
            List<String> ranks,
            long expectedRevision,
            long timestampEpochMillis
        ) {
            return new DataAuthority.PlayerRankCommand(
                manifest(
                    declarationId,
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
                "RECORD_MATCH_START",
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
                "RECORD_MATCH_END",
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
            String declarationId,
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
                declarationId,
                startedAtEpochMillis,
                endedAtEpochMillis,
                timestampEpochMillis
            );
            return new DataAuthority.MatchCommand(
                manifest(
                    declarationId,
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
            String declarationId,
            Long startedAtEpochMillis,
            Long endedAtEpochMillis,
            long timestampEpochMillis
        ) {
            if (startedAtEpochMillis != null) {
                return startedAtEpochMillis;
            }
            if ("RECORD_MATCH_END".equals(declarationId) && endedAtEpochMillis != null) {
                return endedAtEpochMillis;
            }
            return timestampEpochMillis;
        }
    }

    private static DataAuthority.CommandManifest manifest(
        String declarationId,
        String actorId,
        UUID aggregateId,
        long idempotencyEpochMillis,
        long deadlineEpochMillis,
        long expectedRevision
    ) {
        return DataAuthority.CommandManifest.create(
            UUID.randomUUID(),
            declarationId,
            actorId,
            scope(declarationId, aggregateId),
            declarationId + ":" + aggregateId + ":" + idempotencyEpochMillis,
            deadlineEpochMillis,
            "",
            expectedRevision
        );
    }

    private static String scope(String declarationId, UUID aggregateId) {
        Objects.requireNonNull(declarationId, "declarationId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        return AuthorityCommandManifest.declaration(declarationId).aggregateScopePrefix()
            + aggregateId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
