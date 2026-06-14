package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WatermarkedDataAuthorityCacheTest {
    @Test
    void cachesWatermarkedRankSnapshotUntilMaxAgeExpires() {
        UUID playerId = UUID.randomUUID();
        MutableClock clock = new MutableClock(1_000L);
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, clock.millis());
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            },
            Duration.ofSeconds(5),
            clock
        );

        Optional<DataAuthority.PlayerRankSnapshot> first = cache.findRanks(playerId).toCompletableFuture().join();
        Optional<DataAuthority.PlayerRankSnapshot> second = cache.findRanks(playerId).toCompletableFuture().join();

        assertThat(first).contains(snapshot);
        assertThat(second).contains(snapshot);
        assertThat(reads).hasValue(1);
    }

    @Test
    void quotedRankReadReturnsSatisfiedCachedSnapshot() {
        UUID playerId = UUID.randomUUID();
        MutableClock clock = new MutableClock(1_000L);
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, clock.millis());
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            },
            Duration.ofSeconds(5),
            clock
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> first = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();
        clock.advanceMillis(250L);
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> second = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();

        assertThat(first.satisfied()).isTrue();
        assertThat(first.snapshot()).contains(snapshot);
        assertThat(first.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.SATISFIED);
        assertThat(first.quote().observedRevision()).isEqualTo(4L);
        assertThat(first.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);
        assertThat(second.snapshot()).contains(snapshot);
        assertThat(second.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(second.quote().provenance().cachedAtEpochMillis()).isEqualTo(1_000L);
        assertThat(second.quote().provenance().observedAtEpochMillis()).isEqualTo(1_250L);
        assertThat(second.quote().provenance().cacheAgeMillis()).isEqualTo(250L);
        assertThat(second.quote().provenance().maxAgeMillis()).isEqualTo(5_000L);
        assertThat(reads).hasValue(1);
    }

    @Test
    void quotedRankCacheHitPreservesAuthorityBootIdentityFromMiss() {
        UUID playerId = UUID.randomUUID();
        MutableClock clock = new MutableClock(1_000L);
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.AuthorityBootIdentity bootIdentity = authorityBootIdentity();
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, clock.millis());
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                reads.incrementAndGet();
                DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
                    "rank:player:" + id,
                    AuthoritySnapshotInvalidation.PLAYER_RANK,
                    requirement.minimumRevision(),
                    snapshot.revision(),
                    DataAuthority.ReadQuoteStatus.SATISFIED,
                    snapshot.watermark(),
                    null,
                    DataAuthority.ReadProvenance.authority(bootIdentity),
                    DataAuthority.ProjectionDeliveryReceipt.fromWatermark(
                        AuthoritySnapshotInvalidation.PLAYER_RANK,
                        snapshot.watermark()
                    )
                );
                return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(snapshot, quote));
            }
        };
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            rankReader,
            Duration.ofSeconds(5),
            clock
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> first = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();
        clock.advanceMillis(250L);
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> second = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();

        assertThat(first.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);
        assertThat(first.quote().provenance().authorityBoot()).isEqualTo(bootIdentity);
        assertThat(second.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(second.quote().provenance().authorityBoot()).isEqualTo(bootIdentity);
        assertThat(reads).hasValue(1);
    }

    @Test
    void quotedRankReadReportsNotFoundWithoutRevisionRequirement() {
        UUID playerId = UUID.randomUUID();
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(Optional.empty())
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.NOT_FOUND);
        assertThat(read.quote().retryable()).isFalse();
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);
    }

    @Test
    void unwatermarkedRankSnapshotIsRejectedAndNotCached() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }
        );

        Optional<DataAuthority.PlayerRankSnapshot> first = cache.findRanks(playerId).toCompletableFuture().join();
        Optional<DataAuthority.PlayerRankSnapshot> second = cache.findRanks(playerId).toCompletableFuture().join();

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(reads).hasValue(2);
    }

    @Test
    void quotedRankReadReportsUnwatermarkedSnapshot() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(Optional.of(snapshot))
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.UNWATERMARKED);
        assertThat(read.quote().observedRevision()).isEqualTo(4L);
        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).isEmpty();
    }

    @Test
    void rankSnapshotWithMismatchedWatermarkRevisionIsRejected() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            rankWatermark(playerId, 5L, 1_000L)
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(Optional.of(snapshot))
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).isEmpty();
    }

    @Test
    void quotedRankReadReportsMismatchedWatermarkRevision() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            rankWatermark(playerId, 5L, 1_000L)
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(Optional.of(snapshot))
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.REVISION_MISMATCH);
        assertThat(read.quote().observedRevision()).isEqualTo(5L);
    }

    @Test
    void quotedRankReadRejectsMismatchedStateTopic() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.SnapshotWatermark watermark = watermark(
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_profile",
            4L,
            1_000L
        );
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN"),
            4L,
            watermark
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(Optional.of(snapshot))
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE);
        assertThat(read.quote().observedRevision()).isEqualTo(4L);
        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).isEmpty();
    }

    @Test
    void acceptedRankCommandAdvancesReadFloor() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AtomicReference<DataAuthority.PlayerRankSnapshot> snapshot = new AtomicReference<>(
            rankSnapshot(playerId, "VIP", 3L, 1_000L)
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                4L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            )),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(Optional.of(snapshot.get()))
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("VIP");

        DataAuthority.CommandResult result = cache.submit(rankCommand(commandId, playerId, 3L))
            .toCompletableFuture()
            .join();

        assertThat(result.accepted()).isTrue();
        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).isEmpty();

        snapshot.set(rankSnapshot(playerId, "ADMIN", 4L, 1_001L));
        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("ADMIN");
    }

    @Test
    void quotedRankReadTreatsMissingRequiredRevisionAsUnknownOrStale() {
        UUID playerId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            command -> CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                4L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            )),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(Optional.empty())
        );

        cache.submit(rankCommand(commandId, playerId, 3L)).toCompletableFuture().join();

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isFalse();
        assertThat(read.snapshot()).isEmpty();
        assertThat(read.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE);
        assertThat(read.quote().requiredRevision()).isEqualTo(4L);
        assertThat(read.quote().retryable()).isTrue();
    }

    @Test
    void rankInvalidationAdvancesReadFloorAndEvictsCachedSnapshot() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<DataAuthority.PlayerRankSnapshot> snapshot = new AtomicReference<>(
            rankSnapshot(playerId, "VIP", 3L, 1_000L)
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot.get()));
            }
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("VIP");

        AuthoritySnapshotInvalidation invalidation = AuthoritySnapshotInvalidation
            .fromRankSnapshot(rankSnapshot(playerId, "ADMIN", 4L, 1_001L))
            .orElseThrow();
        cache.handleInvalidation(invalidation);

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).isEmpty();
        snapshot.set(rankSnapshot(playerId, "ADMIN", 4L, 1_002L));
        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("ADMIN");
        assertThat(reads).hasValue(3);
    }

    @Test
    void rankRevisionFloorInvalidationFailsClosedUntilProjectionCatchesUp() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<Optional<DataAuthority.PlayerRankSnapshot>> snapshot = new AtomicReference<>(
            Optional.of(rankSnapshot(playerId, "VIP", 3L, 1_000L))
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> CompletableFuture.completedFuture(snapshot.get())
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("VIP");

        AuthoritySnapshotInvalidation floor = AuthoritySnapshotInvalidation
            .revisionFloorFor(rankCommand(UUID.randomUUID(), playerId, 3L), 4L)
            .orElseThrow();
        cache.handleInvalidation(floor);

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> staleRead = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();
        assertThat(staleRead.satisfied()).isFalse();
        assertThat(staleRead.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.STALE_REVISION);
        assertThat(staleRead.quote().requiredRevision()).isEqualTo(4L);
        assertThat(staleRead.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);

        snapshot.set(Optional.empty());
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> missingRead = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .toCompletableFuture()
            .join();
        assertThat(missingRead.satisfied()).isFalse();
        assertThat(missingRead.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE);
        assertThat(missingRead.quote().requiredRevision()).isEqualTo(4L);

        DataAuthority.PlayerRankSnapshot updated = rankSnapshot(playerId, "ADMIN", 4L, 1_001L);
        snapshot.set(Optional.of(updated));
        assertThat(cache.quoteRanks(playerId, DataAuthority.ReadRequirement.eventual()).toCompletableFuture().join())
            .satisfies(read -> {
                assertThat(read.satisfied()).isTrue();
                assertThat(read.snapshot()).contains(updated);
            });
    }

    @Test
    void profileRevisionFloorInvalidationFailsClosedUntilProjectionCatchesUp() {
        UUID playerId = UUID.randomUUID();
        AtomicReference<Optional<DataAuthority.PlayerProfileSnapshot>> snapshot = new AtomicReference<>(
            Optional.of(profileSnapshot(playerId, 2L, 1_000L))
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            id -> CompletableFuture.completedFuture(snapshot.get()),
            unusedRankReader()
        );

        assertThat(cache.findProfile(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerProfileSnapshot::revision)
            .isEqualTo(2L);

        AuthoritySnapshotInvalidation floor = AuthoritySnapshotInvalidation
            .revisionFloorFor(profileCommand(UUID.randomUUID(), playerId, 2L), 3L)
            .orElseThrow();
        cache.handleInvalidation(floor);

        assertThat(cache.findProfile(playerId).toCompletableFuture().join()).isEmpty();

        snapshot.set(Optional.empty());
        assertThat(cache.findProfile(playerId).toCompletableFuture().join()).isEmpty();

        DataAuthority.PlayerProfileSnapshot updated = profileSnapshot(playerId, 3L, 1_001L);
        snapshot.set(Optional.of(updated));
        assertThat(cache.findProfile(playerId).toCompletableFuture().join()).contains(updated);
    }

    @Test
    void olderRankInvalidationDoesNotEvictCachedSnapshot() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, 1_000L);
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).contains(snapshot);

        cache.handleInvalidation(AuthoritySnapshotInvalidation
            .fromRankSnapshot(rankSnapshot(playerId, "VIP", 3L, 999L))
            .orElseThrow());

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).contains(snapshot);
        assertThat(reads).hasValue(1);
    }

    @Test
    void profileInvalidationDoesNotEvictRankSnapshot() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, 1_000L);
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).contains(snapshot);

        cache.handleInvalidation(AuthoritySnapshotInvalidation
            .fromProfileSnapshot(profileSnapshot(playerId, 5L, 1_001L))
            .orElseThrow());

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).contains(snapshot);
        assertThat(reads).hasValue(1);
    }

    @Test
    void unwatermarkedInvalidationIsIgnored() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, 1_000L);
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            }
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).contains(snapshot);

        cache.handleInvalidation(new AuthoritySnapshotInvalidation(
            AuthoritySnapshotInvalidation.SCHEMA_VERSION,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            "rank:player:" + playerId,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            playerId.toString(),
            5L,
            DataAuthority.SnapshotWatermark.unwatermarked(
                "rank:player:" + playerId,
                AuthoritySnapshotInvalidation.PLAYER_RANK,
                playerId.toString(),
                5L
            )
        ));

        assertThat(cache.findRanks(playerId).toCompletableFuture().join()).contains(snapshot);
        assertThat(reads).hasValue(1);
    }

    @Test
    void cachedSnapshotExpiresByLocalCacheAge() {
        UUID playerId = UUID.randomUUID();
        MutableClock clock = new MutableClock(5_000L);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<DataAuthority.PlayerRankSnapshot> snapshot = new AtomicReference<>(
            rankSnapshot(playerId, "VIP", 1L, 1_000L)
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot.get()));
            },
            Duration.ofMillis(10L),
            clock
        );

        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("VIP");
        clock.advanceMillis(10L);
        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("VIP");

        snapshot.set(rankSnapshot(playerId, "ADMIN", 2L, 1_001L));
        clock.advanceMillis(1L);
        assertThat(cache.findRanks(playerId).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerRankSnapshot::primaryRank)
            .isEqualTo("ADMIN");
        assertThat(reads).hasValue(2);
    }

    @Test
    void cachesWatermarkedProfileSnapshot() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            "notch",
            true,
            "paper-1",
            "proxy-1",
            100L,
            Map.of(),
            2L,
            profileWatermark(playerId, 2L, 1_000L)
        );
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            },
            unusedRankReader()
        );

        assertThat(cache.findProfile(playerId).toCompletableFuture().join()).contains(snapshot);
        assertThat(cache.findProfile(playerId).toCompletableFuture().join()).contains(snapshot);
        assertThat(reads).hasValue(1);
    }

    @Test
    void quotedProfileReadReturnsSatisfiedCachedSnapshot() {
        UUID playerId = UUID.randomUUID();
        MutableClock clock = new MutableClock(1_000L);
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerProfileSnapshot snapshot = profileSnapshot(playerId, 2L, 900L);
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            id -> {
                reads.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(snapshot));
            },
            unusedRankReader(),
            Duration.ofSeconds(5),
            clock
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> first = cache
            .quoteProfile(playerId, DataAuthority.ReadRequirement.atLeast(2L))
            .toCompletableFuture()
            .join();
        clock.advanceMillis(250L);
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> second = cache
            .quoteProfile(playerId, DataAuthority.ReadRequirement.atLeast(2L))
            .toCompletableFuture()
            .join();

        assertThat(first.satisfied()).isTrue();
        assertThat(first.snapshot()).contains(snapshot);
        assertThat(first.quote().status()).isEqualTo(DataAuthority.ReadQuoteStatus.SATISFIED);
        assertThat(first.quote().projectionFamily()).isEqualTo(AuthoritySnapshotInvalidation.PLAYER_PROFILE);
        assertThat(first.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.AUTHORITY);
        assertThat(first.quote().deliveryReceipt()).isNotNull();
        assertThat(first.quote().deliveryReceipt().satisfies(
            AuthoritySnapshotInvalidation.PLAYER_PROFILE,
            "player:" + playerId,
            2L
        )).isTrue();
        assertThat(second.snapshot()).contains(snapshot);
        assertThat(second.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(second.quote().provenance().cachedAtEpochMillis()).isEqualTo(1_000L);
        assertThat(second.quote().provenance().observedAtEpochMillis()).isEqualTo(1_250L);
        assertThat(second.quote().provenance().cacheAgeMillis()).isEqualTo(250L);
        assertThat(reads).hasValue(1);
    }

    private static DataAuthority.PlayerProfileSnapshot profileSnapshot(
        UUID playerId,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            "notch",
            true,
            "paper-1",
            "proxy-1",
            100L,
            Map.of(),
            revision,
            profileWatermark(playerId, revision, eventCreatedEpochMillis)
        );
    }

    private static DataAuthority.PlayerRankSnapshot rankSnapshot(
        UUID playerId,
        String primaryRank,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return new DataAuthority.PlayerRankSnapshot(
            playerId,
            primaryRank,
            List.of("DEFAULT", primaryRank),
            revision,
            rankWatermark(playerId, revision, eventCreatedEpochMillis)
        );
    }

    private static DataAuthority.SnapshotWatermark rankWatermark(
        UUID playerId,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return watermark(
            "rank:player:" + playerId,
            "player_rank",
            playerId.toString(),
            "player_rank",
            "state.player_rank",
            revision,
            eventCreatedEpochMillis
        );
    }

    private static DataAuthority.SnapshotWatermark profileWatermark(
        UUID playerId,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return watermark(
            "player:" + playerId,
            "player_profile",
            playerId.toString(),
            "player_profile",
            "state.player_profile",
            revision,
            eventCreatedEpochMillis
        );
    }

    private static DataAuthority.SnapshotWatermark watermark(
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        String commandDomain,
        String stateTopic,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return new DataAuthority.SnapshotWatermark(
            "postgres-authority-state",
            aggregateScope,
            aggregateType,
            aggregateId,
            commandDomain,
            stateTopic,
            aggregateScope,
            UUID.randomUUID(),
            UUID.randomUUID(),
            revision,
            eventCreatedEpochMillis,
            "state-fingerprint-" + revision,
            "event-chain-hash-" + revision
        );
    }

    private static DataAuthority.AuthorityBootIdentity authorityBootIdentity() {
        return new DataAuthority.AuthorityBootIdentity(
            "registry-1",
            "authority-1",
            "startup-fingerprint",
            900L,
            "message-bus-provider",
            "read-contract"
        );
    }

    private static DataAuthority.PlayerRankCommand rankCommand(UUID commandId, UUID playerId, long expectedRevision) {
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.GRANT_RANK,
                "rank-service",
                "rank:player:" + playerId,
                commandId.toString(),
                System.currentTimeMillis() + 1_000L,
                "",
                expectedRevision
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }

    private static DataAuthority.PlayerProfileCommand profileCommand(
        UUID commandId,
        UUID playerId,
        long expectedRevision
    ) {
        return new DataAuthority.PlayerProfileCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
                "profile-service",
                "player:" + playerId,
                commandId.toString(),
                System.currentTimeMillis() + 1_000L,
                "",
                expectedRevision
            ),
            playerId,
            "Notch",
            System.currentTimeMillis(),
            "paper-1",
            "proxy-1",
            "127.0.0.1",
            "world",
            "0,64,0",
            "SURVIVAL",
            1,
            0.5F,
            20.0D,
            20,
            "lastProxySession"
        );
    }

    private static DataAuthority.CommandPort unusedCommandPort() {
        return command -> CompletableFuture.failedFuture(new AssertionError("command port was not expected"));
    }

    private static DataAuthority.PlayerProfileReader unusedProfileReader() {
        return playerId -> CompletableFuture.failedFuture(new AssertionError("profile reader was not expected"));
    }

    private static DataAuthority.PlayerRankReader unusedRankReader() {
        return playerId -> CompletableFuture.failedFuture(new AssertionError("rank reader was not expected"));
    }

    private static final class MutableClock extends Clock {
        private final ZoneId zone;
        private final AtomicReference<Instant> instant;

        private MutableClock(long epochMillis) {
            this(ZoneId.of("UTC"), new AtomicReference<>(Instant.ofEpochMilli(epochMillis)));
        }

        private MutableClock(ZoneId zone, AtomicReference<Instant> instant) {
            this.zone = zone;
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(zone, instant);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advanceMillis(long millis) {
            instant.updateAndGet(current -> current.plusMillis(millis));
        }
    }
}
