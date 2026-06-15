package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Read-only Valkey snapshot-cache adapter for game-node hot reads.
 */
public final class AuthoritySnapshotCacheReader implements DataAuthority.PlayerProfileReader,
    DataAuthority.PresenceReader,
    DataAuthority.PlayerRankReader {

    private final SnapshotCacheStore store;
    private final long maxAgeMillis;
    private final Clock clock;

    public AuthoritySnapshotCacheReader(SnapshotCacheStore store, long maxAgeMillis) {
        this(store, maxAgeMillis, Clock.systemUTC());
    }

    AuthoritySnapshotCacheReader(SnapshotCacheStore store, long maxAgeMillis, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.maxAgeMillis = maxAgeMillis < 0L ? -1L : maxAgeMillis;
        this.clock = clock == null ? Clock.systemUTC() : clock;
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
        String scope = "player:" + playerId;
        Optional<DataAuthority.PlayerProfileSnapshot> snapshot = read(
            "player_profile",
            scope,
            AuthoritySnapshotCacheCodec::profileSnapshot
        );
        return CompletableFuture.completedFuture(quoteSnapshot(
            scope,
            "player_profile",
            snapshot,
            requirement,
            DataAuthority.PlayerProfileSnapshot::revision,
            DataAuthority.PlayerProfileSnapshot::watermark
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
        String scope = "rank:player:" + playerId;
        Optional<DataAuthority.PlayerRankSnapshot> snapshot = read(
            "player_rank",
            scope,
            AuthoritySnapshotCacheCodec::rankSnapshot
        );
        return CompletableFuture.completedFuture(quoteSnapshot(
            scope,
            "player_rank",
            snapshot,
            requirement,
            DataAuthority.PlayerRankSnapshot::revision,
            DataAuthority.PlayerRankSnapshot::watermark
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
        String scope = subject.scope();
        Optional<DataAuthority.PlayerPresenceSnapshot> snapshot = read(
            "presence",
            scope,
            AuthoritySnapshotCacheCodec::presenceSnapshot
        );
        return CompletableFuture.completedFuture(quoteSnapshot(
            scope,
            "presence",
            snapshot,
            requirement,
            DataAuthority.PlayerPresenceSnapshot::revision,
            DataAuthority.PlayerPresenceSnapshot::watermark
        ));
    }

    private <T> Optional<T> read(
        String projectionFamily,
        String aggregateScope,
        Function<Map<String, String>, Optional<T>> decoder
    ) {
        try {
            return store.read(AuthoritySnapshotCacheCodec.cacheKey(projectionFamily, aggregateScope))
                .flatMap(decoder);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private <T> DataAuthority.QuotedRead<T> quoteSnapshot(
        String scope,
        String projectionFamily,
        Optional<T> snapshot,
        DataAuthority.ReadRequirement requirement,
        ToLongFunction<T> revision,
        Function<T, DataAuthority.SnapshotWatermark> watermark
    ) {
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthority.ReadRequirement.orEventual(requirement);
        Optional<T> effectiveSnapshot = snapshot == null ? Optional.empty() : snapshot;
        long requiredRevision = Math.max(0L, effectiveRequirement.minimumRevision());
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
                DataAuthority.ReadProvenance.cache(0L, clock.millis(), maxAgeMillis)
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
        } else if (!DataAuthorityReadContracts.stateTopicMatches(projectionFamily, snapshotWatermark.stateTopic())) {
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
            DataAuthority.ReadProvenance.cache(0L, clock.millis(), maxAgeMillis),
            DataAuthority.ProjectionDeliveryReceipt.fromWatermark(projectionFamily, snapshotWatermark)
        );
        return status == DataAuthority.ReadQuoteStatus.SATISFIED
            ? DataAuthority.QuotedRead.satisfied(value, quote)
            : DataAuthority.QuotedRead.unsatisfied(quote);
    }

    public interface SnapshotCacheStore {
        Optional<Map<String, String>> read(String key);
    }
}
