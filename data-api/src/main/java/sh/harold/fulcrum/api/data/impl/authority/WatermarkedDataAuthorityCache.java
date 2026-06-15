package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Node-local hot snapshot cache that only serves values with authority watermark proof.
 */
public final class WatermarkedDataAuthorityCache implements DataAuthority.CommandPort,
    DataAuthority.CommandSubmissionPort,
    DataAuthority.PlayerProfileReader,
    DataAuthority.PresenceReader,
    DataAuthority.PlayerRankReader {

    public static final Duration DEFAULT_MAX_AGE = Duration.ofMillis(
        DataAuthorityReadContracts.defaultCacheMaxAgeMillis()
    );

    private final DataAuthority.CommandPort commandPort;
    private final DataAuthority.PlayerProfileReader profileReader;
    private final DataAuthority.PresenceReader presenceReader;
    private final DataAuthority.PlayerRankReader rankReader;
    private final AuthoritySnapshotCacheStore snapshotCacheStore;
    private final long maxAgeMillis;
    private final Clock clock;
    private final ConcurrentMap<String, Long> revisionFloors = new java.util.concurrent.ConcurrentHashMap<>();

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader
    ) {
        this(commandPort, profileReader, missingPresenceReader(), rankReader, DEFAULT_MAX_AGE);
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PresenceReader presenceReader,
        DataAuthority.PlayerRankReader rankReader
    ) {
        this(commandPort, profileReader, presenceReader, rankReader, DEFAULT_MAX_AGE);
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge
    ) {
        this(commandPort, profileReader, missingPresenceReader(), rankReader, maxAge, Clock.systemUTC());
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PresenceReader presenceReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge
    ) {
        this(commandPort, profileReader, presenceReader, rankReader, maxAge, Clock.systemUTC());
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge,
        Clock clock
    ) {
        this(
            commandPort,
            profileReader,
            missingPresenceReader(),
            rankReader,
            maxAge,
            clock,
            new InMemoryAuthoritySnapshotCacheStore()
        );
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PresenceReader presenceReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge,
        Clock clock
    ) {
        this(
            commandPort,
            profileReader,
            presenceReader,
            rankReader,
            maxAge,
            clock,
            new InMemoryAuthoritySnapshotCacheStore()
        );
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge,
        Clock clock,
        AuthoritySnapshotCacheStore snapshotCacheStore
    ) {
        this(commandPort, profileReader, missingPresenceReader(), rankReader, maxAge, clock, snapshotCacheStore);
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PresenceReader presenceReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge,
        Clock clock,
        AuthoritySnapshotCacheStore snapshotCacheStore
    ) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.profileReader = Objects.requireNonNull(profileReader, "profileReader");
        this.presenceReader = Objects.requireNonNull(presenceReader, "presenceReader");
        this.rankReader = Objects.requireNonNull(rankReader, "rankReader");
        this.snapshotCacheStore = Objects.requireNonNull(snapshotCacheStore, "snapshotCacheStore");
        Duration effectiveMaxAge = maxAge == null ? DEFAULT_MAX_AGE : maxAge;
        this.maxAgeMillis = effectiveMaxAge.toMillis();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        return commandPort.submit(command)
            .thenApply(result -> {
                if (result != null && result.accepted()) {
                    recordAcceptedCommand(command, result.revision());
                }
                return result;
            });
    }

    @Override
    public CompletionStage<DataAuthority.CommandSubmissionReceipt> submitDurable(
        DataAuthority.AuthorityCommand command
    ) {
        Objects.requireNonNull(command, "command");
        if (commandPort instanceof DataAuthority.CommandSubmissionPort submissionPort) {
            return submissionPort.submitDurable(command);
        }
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
            "Wrapped Data Authority command port does not support durable command submission"
        ));
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID playerId) {
        return quoteProfile(playerId, DataAuthority.ReadRequirement.eventual())
            .thenApply(read -> read.satisfied() ? read.snapshot() : Optional.empty());
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            requirement
        );
        String scope = profileScope(playerId);
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> cached = readProfileCache(
            scope,
            effectiveRequirement
        );
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return profileReader.quoteProfile(playerId, effectiveRequirement)
            .thenApply(read -> acceptFetchedProfile(
                scope,
                read,
                effectiveRequirement
            ));
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerPresenceSnapshot>> findPresence(DataAuthority.Subject subject) {
        return quotePresence(subject, DataAuthority.ReadRequirement.eventual())
            .thenApply(read -> read.satisfied() ? read.snapshot() : Optional.empty());
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot>> quotePresence(
        DataAuthority.Subject subject,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(subject, "subject");
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_PRESENCE,
            requirement
        );
        String scope = presenceScope(subject);
        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> cached = readPresenceCache(
            scope,
            effectiveRequirement
        );
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return presenceReader.quotePresence(subject, effectiveRequirement)
            .thenApply(read -> acceptFetchedPresence(
                scope,
                read,
                effectiveRequirement
            ));
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID playerId) {
        return quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .thenApply(read -> read.satisfied() ? read.snapshot() : Optional.empty());
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            requirement
        );
        String scope = rankScope(playerId);
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> cached = readRankCache(
            scope,
            effectiveRequirement
        );
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return rankReader.quoteRanks(playerId, effectiveRequirement)
            .thenApply(read -> acceptFetchedRank(
                scope,
                read,
                effectiveRequirement
            ));
    }

    public void handleInvalidation(AuthoritySnapshotInvalidation invalidation) {
        Objects.requireNonNull(invalidation, "invalidation");
        if (!invalidation.actionable()) {
            return;
        }
        String scope = invalidation.aggregateScope();
        long revision = invalidation.revision();
        long previousFloor = revisionFloors.getOrDefault(scope, 0L);
        if (revision <= previousFloor) {
            return;
        }
        observe(scope, revision);
        if (AuthoritySnapshotInvalidation.PLAYER_PROFILE.equals(invalidation.projectionFamily())) {
            snapshotCacheStore.invalidateProfile(scope, revision, clock.millis());
        } else if (AuthoritySnapshotInvalidation.PLAYER_PRESENCE.equals(invalidation.projectionFamily())) {
            snapshotCacheStore.invalidatePresence(scope, revision, clock.millis());
        } else if (AuthoritySnapshotInvalidation.PLAYER_RANK.equals(invalidation.projectionFamily())) {
            snapshotCacheStore.invalidateRank(scope, revision, clock.millis());
        }
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> readRankCache(
        String scope,
        DataAuthority.ReadRequirement requirement
    ) {
        Optional<AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerRankSnapshot>> cached =
            snapshotCacheStore.readRank(scope);
        if (cached.isEmpty()) {
            return null;
        }
        AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerRankSnapshot> line = cached.get();
        if (!serveableLine(scope, AuthoritySnapshotInvalidation.PLAYER_RANK, line)) {
            snapshotCacheStore.invalidateRank(scope, line.revision(), clock.millis());
            return null;
        }
        if (expired(line)) {
            snapshotCacheStore.invalidateRank(scope, line.revision(), clock.millis());
            return null;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            Optional.of(line.snapshot()),
            requirement,
            DataAuthority.ReadProvenance.cacheFrom(
                line.provenance(),
                line.cachedAtEpochMillis(),
                clock.millis(),
                maxAgeMillis
            ),
            DataAuthority.PlayerRankSnapshot::revision,
            DataAuthority.PlayerRankSnapshot::watermark
        );
        if (!quote.satisfied()) {
            snapshotCacheStore.invalidateRank(scope, line.revision(), clock.millis());
            return null;
        }
        observe(scope, line.snapshot().watermark().sourceRevision());
        return quote;
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> readPresenceCache(
        String scope,
        DataAuthority.ReadRequirement requirement
    ) {
        Optional<AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerPresenceSnapshot>> cached =
            snapshotCacheStore.readPresence(scope);
        if (cached.isEmpty()) {
            return null;
        }
        AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerPresenceSnapshot> line = cached.get();
        if (!serveableLine(scope, AuthoritySnapshotInvalidation.PLAYER_PRESENCE, line)) {
            snapshotCacheStore.invalidatePresence(scope, line.revision(), clock.millis());
            return null;
        }
        if (expired(line)) {
            snapshotCacheStore.invalidatePresence(scope, line.revision(), clock.millis());
            return null;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
            Optional.of(line.snapshot()),
            requirement,
            DataAuthority.ReadProvenance.cacheFrom(
                line.provenance(),
                line.cachedAtEpochMillis(),
                clock.millis(),
                maxAgeMillis
            ),
            DataAuthority.PlayerPresenceSnapshot::revision,
            DataAuthority.PlayerPresenceSnapshot::watermark
        );
        if (!quote.satisfied()) {
            snapshotCacheStore.invalidatePresence(scope, line.revision(), clock.millis());
            return null;
        }
        observe(scope, line.snapshot().watermark().sourceRevision());
        return quote;
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> readProfileCache(
        String scope,
        DataAuthority.ReadRequirement requirement
    ) {
        Optional<AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerProfileSnapshot>> cached =
            snapshotCacheStore.readProfile(scope);
        if (cached.isEmpty()) {
            return null;
        }
        AuthoritySnapshotCacheStore.SnapshotLine<DataAuthority.PlayerProfileSnapshot> line = cached.get();
        if (!serveableLine(scope, AuthoritySnapshotInvalidation.PLAYER_PROFILE, line)) {
            snapshotCacheStore.invalidateProfile(scope, line.revision(), clock.millis());
            return null;
        }
        if (expired(line)) {
            snapshotCacheStore.invalidateProfile(scope, line.revision(), clock.millis());
            return null;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_PROFILE,
            Optional.of(line.snapshot()),
            requirement,
            DataAuthority.ReadProvenance.cacheFrom(
                line.provenance(),
                line.cachedAtEpochMillis(),
                clock.millis(),
                maxAgeMillis
            ),
            DataAuthority.PlayerProfileSnapshot::revision,
            DataAuthority.PlayerProfileSnapshot::watermark
        );
        if (!quote.satisfied()) {
            snapshotCacheStore.invalidateProfile(scope, line.revision(), clock.millis());
            return null;
        }
        observe(scope, line.snapshot().watermark().sourceRevision());
        return quote;
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> acceptFetchedRank(
        String scope,
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> fetched,
        DataAuthority.ReadRequirement requirement
    ) {
        DataAuthority.ReadProvenance provenance = fetched == null
            ? DataAuthority.ReadProvenance.authority()
            : fetched.quote().provenance();
        if (fetched != null && !fetched.satisfied()) {
            if (requiresLocalMissingQuote(scope, requirement, fetched.quote())) {
                return quoteSnapshot(
                    scope,
                    AuthoritySnapshotInvalidation.PLAYER_RANK,
                    Optional.empty(),
                    requirement,
                    provenance,
                    DataAuthority.PlayerRankSnapshot::revision,
                    DataAuthority.PlayerRankSnapshot::watermark
                );
            }
            snapshotCacheStore.invalidateRank(scope, upstreamRevision(scope, fetched.quote()), clock.millis());
            return fetched;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            fetched == null ? Optional.empty() : fetched.snapshot(),
            requirement,
            provenance,
            DataAuthority.PlayerRankSnapshot::revision,
            DataAuthority.PlayerRankSnapshot::watermark
        );
        if (!quote.satisfied()) {
            snapshotCacheStore.invalidateRank(scope, quote.quote().observedRevision(), clock.millis());
            return quote;
        }
        DataAuthority.PlayerRankSnapshot snapshot = quote.snapshot().orElseThrow();
        observe(scope, snapshot.watermark().sourceRevision());
        snapshotCacheStore.writeRank(AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            scope,
            snapshot.revision(),
            snapshot.watermark(),
            snapshot,
            clock.millis(),
            provenance
        ));
        return quote;
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> acceptFetchedPresence(
        String scope,
        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> fetched,
        DataAuthority.ReadRequirement requirement
    ) {
        DataAuthority.ReadProvenance provenance = fetched == null
            ? DataAuthority.ReadProvenance.authority()
            : fetched.quote().provenance();
        if (fetched != null && !fetched.satisfied()) {
            if (requiresLocalMissingQuote(scope, requirement, fetched.quote())) {
                return quoteSnapshot(
                    scope,
                    AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
                    Optional.empty(),
                    requirement,
                    provenance,
                    DataAuthority.PlayerPresenceSnapshot::revision,
                    DataAuthority.PlayerPresenceSnapshot::watermark
                );
            }
            snapshotCacheStore.invalidatePresence(scope, upstreamRevision(scope, fetched.quote()), clock.millis());
            return fetched;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
            fetched == null ? Optional.empty() : fetched.snapshot(),
            requirement,
            provenance,
            DataAuthority.PlayerPresenceSnapshot::revision,
            DataAuthority.PlayerPresenceSnapshot::watermark
        );
        if (!quote.satisfied()) {
            snapshotCacheStore.invalidatePresence(scope, quote.quote().observedRevision(), clock.millis());
            return quote;
        }
        DataAuthority.PlayerPresenceSnapshot snapshot = quote.snapshot().orElseThrow();
        observe(scope, snapshot.watermark().sourceRevision());
        snapshotCacheStore.writePresence(AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
            scope,
            snapshot.revision(),
            snapshot.watermark(),
            snapshot,
            clock.millis(),
            provenance
        ));
        return quote;
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> acceptFetchedProfile(
        String scope,
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> fetched,
        DataAuthority.ReadRequirement requirement
    ) {
        DataAuthority.ReadProvenance provenance = fetched == null
            ? DataAuthority.ReadProvenance.authority()
            : fetched.quote().provenance();
        if (fetched != null && !fetched.satisfied()) {
            if (requiresLocalMissingQuote(scope, requirement, fetched.quote())) {
                return quoteSnapshot(
                    scope,
                    AuthoritySnapshotInvalidation.PLAYER_PROFILE,
                    Optional.empty(),
                    requirement,
                    provenance,
                    DataAuthority.PlayerProfileSnapshot::revision,
                    DataAuthority.PlayerProfileSnapshot::watermark
                );
            }
            snapshotCacheStore.invalidateProfile(scope, upstreamRevision(scope, fetched.quote()), clock.millis());
            return fetched;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_PROFILE,
            fetched == null ? Optional.empty() : fetched.snapshot(),
            requirement,
            provenance,
            DataAuthority.PlayerProfileSnapshot::revision,
            DataAuthority.PlayerProfileSnapshot::watermark
        );
        if (!quote.satisfied()) {
            snapshotCacheStore.invalidateProfile(scope, quote.quote().observedRevision(), clock.millis());
            return quote;
        }
        DataAuthority.PlayerProfileSnapshot snapshot = quote.snapshot().orElseThrow();
        observe(scope, snapshot.watermark().sourceRevision());
        snapshotCacheStore.writeProfile(AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_PROFILE,
            scope,
            snapshot.revision(),
            snapshot.watermark(),
            snapshot,
            clock.millis(),
            provenance
        ));
        return quote;
    }

    private <T> DataAuthority.QuotedRead<T> quoteSnapshot(
        String scope,
        String projectionFamily,
        Optional<T> snapshot,
        DataAuthority.ReadRequirement requirement,
        DataAuthority.ReadProvenance provenance,
        ToLongFunction<T> revision,
        Function<T, DataAuthority.SnapshotWatermark> watermark
    ) {
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthority.ReadRequirement.orEventual(requirement);
        long requiredRevision = requiredRevision(scope, effectiveRequirement);
        Optional<T> effectiveSnapshot = snapshot == null ? Optional.empty() : snapshot;
        if (effectiveSnapshot.isEmpty()) {
            DataAuthority.ReadQuoteStatus status = requiredRevision > 0L
                ? DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE
                : DataAuthority.ReadQuoteStatus.NOT_FOUND;
            return DataAuthority.QuotedRead.unsatisfied(new DataAuthority.ReadQuote(
                scope,
                projectionFamily,
                requiredRevision,
                0L,
                status,
                null,
                null,
                provenance
            ));
        }

        T value = effectiveSnapshot.get();
        DataAuthority.SnapshotWatermark snapshotWatermark = watermark.apply(value);
        long snapshotRevision = Math.max(0L, revision.applyAsLong(value));
        long observedRevision = snapshotWatermark == null
            ? snapshotRevision
            : Math.max(snapshotRevision, snapshotWatermark.sourceRevision());
        DataAuthority.ReadQuoteStatus status;
        if (snapshotWatermark == null || !snapshotWatermark.watermarked()) {
            status = DataAuthority.ReadQuoteStatus.UNWATERMARKED;
        } else if (!scope.equals(snapshotWatermark.aggregateScope())) {
            status = DataAuthority.ReadQuoteStatus.SCOPE_MISMATCH;
        } else if (!stateTopicMatches(projectionFamily, snapshotWatermark.stateTopic())) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (effectiveRequirement.visibilityToken() != null
            && !snapshotWatermark.satisfies(effectiveRequirement.visibilityToken())) {
            status = DataAuthority.ReadQuoteStatus.VISIBILITY_TOKEN_MISMATCH;
        } else if (snapshotWatermark.sourceRevision() != snapshotRevision) {
            status = DataAuthority.ReadQuoteStatus.REVISION_MISMATCH;
        } else if (snapshotRevision < requiredRevision || snapshotWatermark.sourceRevision() < requiredRevision) {
            status = DataAuthority.ReadQuoteStatus.STALE_REVISION;
        } else if (effectiveRequirement.hasMaxAge()
            && snapshotWatermark.staleAt(clock.millis(), effectiveRequirement.maxAgeMillis())) {
            status = DataAuthority.ReadQuoteStatus.EXPIRED;
        } else {
            status = DataAuthority.ReadQuoteStatus.SATISFIED;
        }

        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            scope,
            projectionFamily,
            requiredRevision,
            observedRevision,
            status,
            snapshotWatermark,
            null,
            provenance,
            DataAuthority.ProjectionDeliveryReceipt.fromWatermark(projectionFamily, snapshotWatermark)
        );
        return status == DataAuthority.ReadQuoteStatus.SATISFIED
            ? DataAuthority.QuotedRead.satisfied(value, quote)
            : DataAuthority.QuotedRead.unsatisfied(quote);
    }

    private boolean requiresLocalMissingQuote(
        String scope,
        DataAuthority.ReadRequirement requirement,
        DataAuthority.ReadQuote upstreamQuote
    ) {
        DataAuthority.ReadQuoteStatus status = upstreamQuote.status();
        if (status != DataAuthority.ReadQuoteStatus.NOT_FOUND
            && status != DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE) {
            return false;
        }
        long requiredRevision = requiredRevision(scope, DataAuthority.ReadRequirement.orEventual(requirement));
        return requiredRevision > upstreamQuote.requiredRevision();
    }

    private long requiredRevision(String scope, DataAuthority.ReadRequirement requirement) {
        return Math.max(
            requirement.minimumRevision(),
            revisionFloors.getOrDefault(scope, 0L)
        );
    }

    private <T> boolean acceptedSnapshot(
        String scope,
        T snapshot,
        ToLongFunction<T> revision,
        Function<T, DataAuthority.SnapshotWatermark> watermark
    ) {
        DataAuthority.SnapshotWatermark snapshotWatermark = watermark.apply(snapshot);
        if (snapshotWatermark == null || !snapshotWatermark.watermarked()) {
            return false;
        }
        long snapshotRevision = revision.applyAsLong(snapshot);
        if (!scope.equals(snapshotWatermark.aggregateScope())) {
            return false;
        }
        if (snapshotWatermark.sourceRevision() != snapshotRevision) {
            return false;
        }
        String projectionFamily = projectionFamilyForScope(scope);
        if (projectionFamily != null && !stateTopicMatches(projectionFamily, snapshotWatermark.stateTopic())) {
            return false;
        }
        long requiredRevision = revisionFloors.getOrDefault(scope, 0L);
        return snapshotRevision >= requiredRevision && snapshotWatermark.sourceRevision() >= requiredRevision;
    }

    private boolean expired(AuthoritySnapshotCacheStore.SnapshotLine<?> cached) {
        return maxAgeMillis >= 0L && cached.cachedAtEpochMillis() + maxAgeMillis < clock.millis();
    }

    private boolean serveableLine(
        String scope,
        String projectionFamily,
        AuthoritySnapshotCacheStore.SnapshotLine<?> line
    ) {
        return line.serveable()
            && projectionFamily.equals(line.projectionFamily())
            && scope.equals(line.aggregateScope());
    }

    private void recordAcceptedCommand(DataAuthority.AuthorityCommand command, long revision) {
        String scope = scopeFor(command);
        if (scope == null) {
            return;
        }
        observe(scope, revision);
        String projectionFamily = projectionFamilyForScope(scope);
        if (AuthoritySnapshotInvalidation.PLAYER_PROFILE.equals(projectionFamily)) {
            snapshotCacheStore.invalidateProfile(scope, revision, clock.millis());
        } else if (AuthoritySnapshotInvalidation.PLAYER_PRESENCE.equals(projectionFamily)) {
            snapshotCacheStore.invalidatePresence(scope, revision, clock.millis());
        } else if (AuthoritySnapshotInvalidation.PLAYER_RANK.equals(projectionFamily)) {
            snapshotCacheStore.invalidateRank(scope, revision, clock.millis());
        }
    }

    private void observe(String scope, long revision) {
        if (revision > 0L) {
            revisionFloors.merge(scope, revision, Math::max);
        }
    }

    private static String scopeFor(DataAuthority.AuthorityCommand command) {
        if (command instanceof DataAuthority.PlayerRankCommand rankCommand) {
            return rankScope(rankCommand.playerId());
        }
        if (command instanceof DataAuthority.PlayerProfileCommand profileCommand) {
            return profileScope(profileCommand.playerId());
        }
        if (command instanceof DataAuthority.PlayerSessionCommand sessionCommand) {
            return presenceScope(sessionCommand.subject());
        }
        return null;
    }

    private static String profileScope(UUID playerId) {
        return "player:" + playerId;
    }

    private static String rankScope(UUID playerId) {
        return "rank:player:" + playerId;
    }

    private static String presenceScope(DataAuthority.Subject subject) {
        return subject.scope();
    }

    private static boolean stateTopicMatches(String projectionFamily, String stateTopic) {
        return DataAuthorityReadContracts.stateTopicMatches(projectionFamily, stateTopic);
    }

    private static String projectionFamilyForScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        if (scope.startsWith("rank:player:")) {
            return AuthoritySnapshotInvalidation.PLAYER_RANK;
        }
        if (scope.startsWith("player:")) {
            return AuthoritySnapshotInvalidation.PLAYER_PROFILE;
        }
        if (scope.startsWith(DataAuthority.Subject.SCOPE_PREFIX)) {
            return AuthoritySnapshotInvalidation.PLAYER_PRESENCE;
        }
        return null;
    }

    private long upstreamRevision(String scope, DataAuthority.ReadQuote upstreamQuote) {
        if (upstreamQuote == null) {
            return revisionFloors.getOrDefault(scope, 0L);
        }
        return Math.max(upstreamQuote.observedRevision(), upstreamQuote.requiredRevision());
    }

    private static DataAuthority.PresenceReader missingPresenceReader() {
        return subject -> CompletableFuture.failedFuture(new UnsupportedOperationException(
            "Wrapped Data Authority presence reader is not configured"
        ));
    }
}
