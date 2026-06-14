package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Node-local hot snapshot cache that only serves values with authority watermark proof.
 */
public final class WatermarkedDataAuthorityCache implements DataAuthority.CommandPort,
    DataAuthority.PlayerProfileReader,
    DataAuthority.PlayerRankReader {

    public static final Duration DEFAULT_MAX_AGE = Duration.ofMillis(
        DataAuthorityReadContracts.defaultCacheMaxAgeMillis()
    );

    private final DataAuthority.CommandPort commandPort;
    private final DataAuthority.PlayerProfileReader profileReader;
    private final DataAuthority.PlayerRankReader rankReader;
    private final long maxAgeMillis;
    private final Clock clock;
    private final ConcurrentMap<String, Cached<DataAuthority.PlayerProfileSnapshot>> profileSnapshots =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Cached<DataAuthority.PlayerRankSnapshot>> rankSnapshots =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> revisionFloors = new ConcurrentHashMap<>();

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader
    ) {
        this(commandPort, profileReader, rankReader, DEFAULT_MAX_AGE);
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge
    ) {
        this(commandPort, profileReader, rankReader, maxAge, Clock.systemUTC());
    }

    public WatermarkedDataAuthorityCache(
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        Duration maxAge,
        Clock clock
    ) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.profileReader = Objects.requireNonNull(profileReader, "profileReader");
        this.rankReader = Objects.requireNonNull(rankReader, "rankReader");
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
            profileSnapshots.remove(scope);
        } else if (AuthoritySnapshotInvalidation.PLAYER_RANK.equals(invalidation.projectionFamily())) {
            rankSnapshots.remove(scope);
        }
    }

    private <T> Optional<T> readCache(
        String scope,
        ConcurrentMap<String, Cached<T>> cache,
        ToLongFunction<T> revision,
        Function<T, DataAuthority.SnapshotWatermark> watermark
    ) {
        Cached<T> cached = cache.get(scope);
        if (cached == null) {
            return Optional.empty();
        }
        if (expired(cached) || !acceptedSnapshot(scope, cached.value(), revision, watermark)) {
            cache.remove(scope, cached);
            return Optional.empty();
        }
        observe(scope, watermark.apply(cached.value()).sourceRevision());
        return Optional.of(cached.value());
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> readRankCache(
        String scope,
        DataAuthority.ReadRequirement requirement
    ) {
        Cached<DataAuthority.PlayerRankSnapshot> cached = rankSnapshots.get(scope);
        if (cached == null) {
            return null;
        }
        if (expired(cached)) {
            rankSnapshots.remove(scope, cached);
            return null;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            Optional.of(cached.value()),
            requirement,
            DataAuthority.ReadProvenance.cacheFrom(
                cached.provenance(),
                cached.cachedAtEpochMillis(),
                clock.millis(),
                maxAgeMillis
            ),
            DataAuthority.PlayerRankSnapshot::revision,
            DataAuthority.PlayerRankSnapshot::watermark
        );
        if (!quote.satisfied()) {
            rankSnapshots.remove(scope, cached);
            return null;
        }
        observe(scope, cached.value().watermark().sourceRevision());
        return quote;
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> readProfileCache(
        String scope,
        DataAuthority.ReadRequirement requirement
    ) {
        Cached<DataAuthority.PlayerProfileSnapshot> cached = profileSnapshots.get(scope);
        if (cached == null) {
            return null;
        }
        if (expired(cached)) {
            profileSnapshots.remove(scope, cached);
            return null;
        }
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> quote = quoteSnapshot(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_PROFILE,
            Optional.of(cached.value()),
            requirement,
            DataAuthority.ReadProvenance.cacheFrom(
                cached.provenance(),
                cached.cachedAtEpochMillis(),
                clock.millis(),
                maxAgeMillis
            ),
            DataAuthority.PlayerProfileSnapshot::revision,
            DataAuthority.PlayerProfileSnapshot::watermark
        );
        if (!quote.satisfied()) {
            profileSnapshots.remove(scope, cached);
            return null;
        }
        observe(scope, cached.value().watermark().sourceRevision());
        return quote;
    }

    private <T> Optional<T> acceptFetched(
        String scope,
        Optional<T> fetched,
        ConcurrentMap<String, Cached<T>> cache,
        ToLongFunction<T> revision,
        Function<T, DataAuthority.SnapshotWatermark> watermark
    ) {
        if (fetched.isEmpty()) {
            cache.remove(scope);
            return Optional.empty();
        }
        T snapshot = fetched.get();
        if (!acceptedSnapshot(scope, snapshot, revision, watermark)) {
            cache.remove(scope);
            return Optional.empty();
        }
        observe(scope, watermark.apply(snapshot).sourceRevision());
        cache.put(scope, new Cached<>(snapshot, clock.millis()));
        return fetched;
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
            rankSnapshots.remove(scope);
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
            rankSnapshots.remove(scope);
            return quote;
        }
        DataAuthority.PlayerRankSnapshot snapshot = quote.snapshot().orElseThrow();
        observe(scope, snapshot.watermark().sourceRevision());
        rankSnapshots.put(scope, new Cached<>(snapshot, clock.millis(), provenance));
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
            profileSnapshots.remove(scope);
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
            profileSnapshots.remove(scope);
            return quote;
        }
        DataAuthority.PlayerProfileSnapshot snapshot = quote.snapshot().orElseThrow();
        observe(scope, snapshot.watermark().sourceRevision());
        profileSnapshots.put(scope, new Cached<>(snapshot, clock.millis(), provenance));
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
        } else if (!expectedStateTopic(projectionFamily).equals(snapshotWatermark.stateTopic())) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
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
        String expectedStateTopic = expectedStateTopicForScope(scope);
        if (expectedStateTopic != null && !expectedStateTopic.equals(snapshotWatermark.stateTopic())) {
            return false;
        }
        long requiredRevision = revisionFloors.getOrDefault(scope, 0L);
        return snapshotRevision >= requiredRevision && snapshotWatermark.sourceRevision() >= requiredRevision;
    }

    private boolean expired(Cached<?> cached) {
        return maxAgeMillis >= 0L && cached.cachedAtEpochMillis() + maxAgeMillis < clock.millis();
    }

    private void recordAcceptedCommand(DataAuthority.AuthorityCommand command, long revision) {
        String scope = scopeFor(command);
        if (scope == null) {
            return;
        }
        observe(scope, revision);
        profileSnapshots.remove(scope);
        rankSnapshots.remove(scope);
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
            return profileScope(sessionCommand.playerId());
        }
        return null;
    }

    private static String profileScope(UUID playerId) {
        return "player:" + playerId;
    }

    private static String rankScope(UUID playerId) {
        return "rank:player:" + playerId;
    }

    private static String expectedStateTopic(String projectionFamily) {
        return "state." + projectionFamily;
    }

    private static String expectedStateTopicForScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        if (scope.startsWith("rank:player:")) {
            return expectedStateTopic(AuthoritySnapshotInvalidation.PLAYER_RANK);
        }
        if (scope.startsWith("player:")) {
            return expectedStateTopic(AuthoritySnapshotInvalidation.PLAYER_PROFILE);
        }
        return null;
    }

    private record Cached<T>(T value, long cachedAtEpochMillis, DataAuthority.ReadProvenance provenance) {
        private Cached(T value, long cachedAtEpochMillis) {
            this(value, cachedAtEpochMillis, DataAuthority.ReadProvenance.unknown());
        }

        private Cached {
            provenance = provenance == null ? DataAuthority.ReadProvenance.unknown() : provenance;
        }
    }
}
