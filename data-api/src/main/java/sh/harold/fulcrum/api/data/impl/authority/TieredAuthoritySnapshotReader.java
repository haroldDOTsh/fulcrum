package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Hot-read path: Valkey snapshot cache first, then Cassandra hot state.
 */
public final class TieredAuthoritySnapshotReader implements DataAuthority.PlayerProfileReader,
    DataAuthority.PresenceReader,
    DataAuthority.PlayerRankReader {
    private final DataAuthority.PlayerProfileReader cacheProfileReader;
    private final DataAuthority.PresenceReader cachePresenceReader;
    private final DataAuthority.PlayerRankReader cacheRankReader;
    private final DataAuthority.PlayerProfileReader hotStateProfileReader;
    private final DataAuthority.PresenceReader hotStatePresenceReader;
    private final DataAuthority.PlayerRankReader hotStateRankReader;

    public TieredAuthoritySnapshotReader(
        DataAuthority.PlayerProfileReader cacheProfileReader,
        DataAuthority.PresenceReader cachePresenceReader,
        DataAuthority.PlayerRankReader cacheRankReader,
        DataAuthority.PlayerProfileReader hotStateProfileReader,
        DataAuthority.PresenceReader hotStatePresenceReader,
        DataAuthority.PlayerRankReader hotStateRankReader
    ) {
        this.cacheProfileReader = Objects.requireNonNull(cacheProfileReader, "cacheProfileReader");
        this.cachePresenceReader = Objects.requireNonNull(cachePresenceReader, "cachePresenceReader");
        this.cacheRankReader = Objects.requireNonNull(cacheRankReader, "cacheRankReader");
        this.hotStateProfileReader = Objects.requireNonNull(hotStateProfileReader, "hotStateProfileReader");
        this.hotStatePresenceReader = Objects.requireNonNull(hotStatePresenceReader, "hotStatePresenceReader");
        this.hotStateRankReader = Objects.requireNonNull(hotStateRankReader, "hotStateRankReader");
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
        return cacheProfileReader.quoteProfile(playerId, requirement)
            .thenCompose(cacheRead -> cacheRead.satisfied()
                ? java.util.concurrent.CompletableFuture.completedFuture(cacheRead)
                : hotStateProfileReader.quoteProfile(playerId, requirement));
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
        return cachePresenceReader.quotePresence(subject, requirement)
            .thenCompose(cacheRead -> cacheRead.satisfied()
                ? java.util.concurrent.CompletableFuture.completedFuture(cacheRead)
                : hotStatePresenceReader.quotePresence(subject, requirement));
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
        return cacheRankReader.quoteRanks(playerId, requirement)
            .thenCompose(cacheRead -> cacheRead.satisfied()
                ? java.util.concurrent.CompletableFuture.completedFuture(cacheRead)
                : hotStateRankReader.quoteRanks(playerId, requirement));
    }
}
