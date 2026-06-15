package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
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
    void cacheMissReadsHotStateThenCacheHitReportsCache() {
        UUID playerId = UUID.randomUUID();
        MutableClock clock = new MutableClock(1_000L);
        AtomicInteger reads = new AtomicInteger();
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, clock.millis());
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            new DataAuthority.PlayerRankReader() {
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
                        DataAuthority.ReadProvenance.hotState(),
                        DataAuthority.ProjectionDeliveryReceipt.fromWatermark(
                            AuthoritySnapshotInvalidation.PLAYER_RANK,
                            snapshot.watermark()
                        )
                    );
                    return CompletableFuture.completedFuture(DataAuthority.QuotedRead.satisfied(snapshot, quote));
                }
            },
            Duration.ofSeconds(5),
            clock
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> first = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();
        clock.advanceMillis(250L);
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> second = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(first.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.HOT_STATE);
        assertThat(second.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(reads).hasValue(1);
    }

    @Test
    void snapshotCacheStoreHitIsReadBeforeHotState() {
        UUID playerId = UUID.randomUUID();
        String scope = "rank:player:" + playerId;
        MutableClock clock = new MutableClock(1_000L);
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, 900L);
        List<String> events = new ArrayList<>();
        RecordingSnapshotCacheStore snapshotStore = new RecordingSnapshotCacheStore(events);
        snapshotStore.writeRank(AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            scope,
            snapshot.revision(),
            snapshot.watermark(),
            snapshot,
            900L,
            DataAuthority.ReadProvenance.hotState()
        ));
        events.clear();
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            unusedRankReader(),
            Duration.ofSeconds(5),
            clock,
            snapshotStore
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).contains(snapshot);
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(read.quote().provenance().cachedAtEpochMillis()).isEqualTo(900L);
        assertThat(read.quote().provenance().observedAtEpochMillis()).isEqualTo(1_000L);
        assertThat(events).containsExactly("cache:readRank:" + scope);
    }

    @Test
    void snapshotCacheStoreMissReadsHotStateThenFillsLine() {
        UUID playerId = UUID.randomUUID();
        String scope = "rank:player:" + playerId;
        MutableClock clock = new MutableClock(1_000L);
        DataAuthority.PlayerRankSnapshot snapshot = rankSnapshot(playerId, "ADMIN", 4L, clock.millis());
        List<String> events = new ArrayList<>();
        RecordingSnapshotCacheStore snapshotStore = new RecordingSnapshotCacheStore(events);
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.failedFuture(new AssertionError("findRanks was not expected"));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                events.add("hot:quoteRank:" + scope);
                return CompletableFuture.completedFuture(hotStateRankRead(scope, snapshot, requirement));
            }
        };
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            rankReader,
            Duration.ofSeconds(5),
            clock,
            snapshotStore
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> first = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();
        clock.advanceMillis(250L);
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> second = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(first.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.HOT_STATE);
        assertThat(second.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(events).containsExactly(
            "cache:readRank:" + scope,
            "hot:quoteRank:" + scope,
            "cache:writeRank:" + scope + ":4",
            "cache:readRank:" + scope
        );
    }

    @Test
    void presenceMissReadsHotStateThenFillsSnapshotCache() {
        UUID subjectId = UUID.randomUUID();
        String scope = DataAuthority.Subject.SCOPE_PREFIX + subjectId;
        MutableClock clock = new MutableClock(1_000L);
        DataAuthority.PlayerPresenceSnapshot snapshot = presenceSnapshot(subjectId, 4L, clock.millis());
        List<String> events = new ArrayList<>();
        RecordingSnapshotCacheStore snapshotStore = new RecordingSnapshotCacheStore(events);
        DataAuthority.Subject subject = DataAuthority.Subject.player(subjectId);
        DataAuthority.PresenceReader presenceReader = new DataAuthority.PresenceReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerPresenceSnapshot>> findPresence(
                DataAuthority.Subject value
            ) {
                return CompletableFuture.failedFuture(new AssertionError("findPresence was not expected"));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot>> quotePresence(
                DataAuthority.Subject value,
                DataAuthority.ReadRequirement requirement
            ) {
                events.add("hot:quotePresence:" + scope);
                return CompletableFuture.completedFuture(hotStatePresenceRead(scope, snapshot, requirement));
            }
        };
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            presenceReader,
            unusedRankReader(),
            Duration.ofSeconds(5),
            clock,
            snapshotStore
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> first = cache
            .quotePresence(subject, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();
        clock.advanceMillis(250L);
        DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> second = cache
            .quotePresence(subject, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(first.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.HOT_STATE);
        assertThat(second.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.CACHE);
        assertThat(events).containsExactly(
            "cache:readPresence:" + scope,
            "hot:quotePresence:" + scope,
            "cache:writePresence:" + scope + ":4",
            "cache:readPresence:" + scope
        );
    }

    @Test
    void staleSnapshotCacheStoreLineFallsThroughToHotState() {
        UUID playerId = UUID.randomUUID();
        String scope = "rank:player:" + playerId;
        MutableClock clock = new MutableClock(1_000L);
        DataAuthority.PlayerRankSnapshot stale = rankSnapshot(playerId, "VIP", 3L, 900L);
        DataAuthority.PlayerRankSnapshot updated = rankSnapshot(playerId, "ADMIN", 4L, clock.millis());
        List<String> events = new ArrayList<>();
        RecordingSnapshotCacheStore snapshotStore = new RecordingSnapshotCacheStore(events);
        snapshotStore.writeRank(AuthoritySnapshotCacheStore.SnapshotLine.snapshot(
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            scope,
            stale.revision(),
            stale.watermark(),
            stale,
            900L,
            DataAuthority.ReadProvenance.hotState()
        ));
        events.clear();
        DataAuthority.PlayerRankReader rankReader = new DataAuthority.PlayerRankReader() {
            @Override
            public CompletableFuture<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID id) {
                return CompletableFuture.failedFuture(new AssertionError("findRanks was not expected"));
            }

            @Override
            public CompletableFuture<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
                UUID id,
                DataAuthority.ReadRequirement requirement
            ) {
                events.add("hot:quoteRank:" + scope);
                return CompletableFuture.completedFuture(hotStateRankRead(scope, updated, requirement));
            }
        };
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            rankReader,
            Duration.ofSeconds(5),
            clock,
            snapshotStore
        );

        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read = cache
            .quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(4L))
            .toCompletableFuture()
            .join();

        assertThat(read.satisfied()).isTrue();
        assertThat(read.snapshot()).contains(updated);
        assertThat(read.quote().provenance().sourceTier()).isEqualTo(DataAuthority.ReadSourceTier.HOT_STATE);
        assertThat(events).containsExactly(
            "cache:readRank:" + scope,
            "cache:invalidateRank:" + scope + ":3",
            "hot:quoteRank:" + scope,
            "cache:writeRank:" + scope + ":4"
        );
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
    void durableSubmissionDelegatesToWrappedCommandPort() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand command = rankCommand(UUID.randomUUID(), playerId, 3L);
        DataAuthority.CommandSubmissionReceipt receipt = submissionReceipt(command, 2, 17L);
        AtomicReference<DataAuthority.AuthorityCommand> submitted = new AtomicReference<>();
        class DurablePort implements DataAuthority.CommandPort, DataAuthority.CommandSubmissionPort {
            @Override
            public CompletableFuture<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
                return CompletableFuture.failedFuture(new AssertionError("submit was not expected"));
            }

            @Override
            public CompletableFuture<DataAuthority.CommandSubmissionReceipt> submitDurable(
                DataAuthority.AuthorityCommand command
            ) {
                submitted.set(command);
                return CompletableFuture.completedFuture(receipt);
            }
        }
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            new DurablePort(),
            unusedProfileReader(),
            unusedRankReader()
        );

        DataAuthority.CommandSubmissionReceipt result = cache.submitDurable(command)
            .toCompletableFuture()
            .join();

        assertThat(result).isSameAs(receipt);
        assertThat(submitted).hasValue(command);
    }

    @Test
    void durableSubmissionFailsWhenWrappedCommandPortDoesNotSupportIt() {
        UUID playerId = UUID.randomUUID();
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            unusedRankReader()
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache
                .submitDurable(rankCommand(UUID.randomUUID(), playerId, 3L))
                .toCompletableFuture()
                .join())
            .hasCauseInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Wrapped Data Authority command port does not support durable command submission");
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
    void sessionRevisionFloorInvalidationFailsClosedUntilPresenceProjectionCatchesUp() {
        UUID subjectId = UUID.randomUUID();
        AtomicReference<Optional<DataAuthority.PlayerPresenceSnapshot>> snapshot = new AtomicReference<>(
            Optional.of(presenceSnapshot(subjectId, 2L, 1_000L))
        );
        DataAuthority.Subject subject = DataAuthority.Subject.player(subjectId);
        WatermarkedDataAuthorityCache cache = new WatermarkedDataAuthorityCache(
            unusedCommandPort(),
            unusedProfileReader(),
            value -> CompletableFuture.completedFuture(snapshot.get()),
            unusedRankReader()
        );

        assertThat(cache.findPresence(subject).toCompletableFuture().join())
            .get()
            .extracting(DataAuthority.PlayerPresenceSnapshot::revision)
            .isEqualTo(2L);

        AuthoritySnapshotInvalidation floor = AuthoritySnapshotInvalidation
            .revisionFloorFor(sessionCommand(UUID.randomUUID(), subjectId, 2L), 3L)
            .orElseThrow();
        cache.handleInvalidation(floor);

        assertThat(cache.findPresence(subject).toCompletableFuture().join()).isEmpty();

        snapshot.set(Optional.empty());
        assertThat(cache.findPresence(subject).toCompletableFuture().join()).isEmpty();

        DataAuthority.PlayerPresenceSnapshot updated = presenceSnapshot(subjectId, 3L, 1_001L);
        snapshot.set(Optional.of(updated));
        assertThat(cache.findPresence(subject).toCompletableFuture().join()).contains(updated);
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
    void rankInvalidationAcceptsRouteStateTopicAlias() {
        UUID playerId = UUID.randomUUID();
        AuthoritySnapshotInvalidation invalidation = new AuthoritySnapshotInvalidation(
            AuthoritySnapshotInvalidation.SCHEMA_VERSION,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            "rank:player:" + playerId,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            playerId.toString(),
            5L,
            watermark(
                "rank:player:" + playerId,
                AuthoritySnapshotInvalidation.PLAYER_RANK,
                playerId.toString(),
                "rank",
                "state.rank",
                5L,
                1_000L
            )
        );

        assertThat(invalidation.actionable()).isTrue();
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

    private static DataAuthority.PlayerPresenceSnapshot presenceSnapshot(
        UUID subjectId,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return new DataAuthority.PlayerPresenceSnapshot(
            subjectId,
            subjectId,
            "Notch",
            true,
            "paper-1",
            "proxy-1",
            UUID.randomUUID(),
            eventCreatedEpochMillis,
            revision,
            presenceWatermark(subjectId, revision, eventCreatedEpochMillis)
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

    private static DataAuthority.SnapshotWatermark presenceWatermark(
        UUID subjectId,
        long revision,
        long eventCreatedEpochMillis
    ) {
        return watermark(
            DataAuthority.Subject.SCOPE_PREFIX + subjectId,
            AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
            subjectId.toString(),
            "session",
            "state.session",
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
                "GRANT_RANK",
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

    private static DataAuthority.PlayerSessionCommand sessionCommand(
        UUID commandId,
        UUID subjectId,
        long expectedRevision
    ) {
        return new DataAuthority.PlayerSessionCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "START_SESSION",
                "session-service",
                DataAuthority.Subject.SCOPE_PREFIX + subjectId,
                commandId.toString(),
                System.currentTimeMillis() + 1_000L,
                "",
                expectedRevision
            ),
            subjectId,
            "Notch",
            UUID.randomUUID(),
            System.currentTimeMillis(),
            "paper-1",
            "proxy-1",
            "127.0.0.1",
            765,
            null
        );
    }

    private static DataAuthority.CommandSubmissionReceipt submissionReceipt(
        DataAuthority.AuthorityCommand command,
        int partition,
        long offset
    ) {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        return new DataAuthority.CommandSubmissionReceipt(
            command.commandId(),
            command.declarationId(),
            command.scope(),
            route.domain(),
            route.commandTopic(),
            route.responseTopic(),
            route.eventTopic(),
            route.stateTopic(),
            route.partitionKey(),
            partition,
            offset,
            System.currentTimeMillis(),
            command.provenance()
        );
    }

    private static DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> hotStateRankRead(
        String scope,
        DataAuthority.PlayerRankSnapshot snapshot,
        DataAuthority.ReadRequirement requirement
    ) {
        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_RANK,
            requirement.minimumRevision(),
            snapshot.revision(),
            DataAuthority.ReadQuoteStatus.SATISFIED,
            snapshot.watermark(),
            null,
            DataAuthority.ReadProvenance.hotState(),
            DataAuthority.ProjectionDeliveryReceipt.fromWatermark(
                AuthoritySnapshotInvalidation.PLAYER_RANK,
                snapshot.watermark()
            )
        );
        return DataAuthority.QuotedRead.satisfied(snapshot, quote);
    }

    private static DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> hotStatePresenceRead(
        String scope,
        DataAuthority.PlayerPresenceSnapshot snapshot,
        DataAuthority.ReadRequirement requirement
    ) {
        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            scope,
            AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
            requirement.minimumRevision(),
            snapshot.revision(),
            DataAuthority.ReadQuoteStatus.SATISFIED,
            snapshot.watermark(),
            null,
            DataAuthority.ReadProvenance.hotState(),
            DataAuthority.ProjectionDeliveryReceipt.fromWatermark(
                AuthoritySnapshotInvalidation.PLAYER_PRESENCE,
                snapshot.watermark()
            )
        );
        return DataAuthority.QuotedRead.satisfied(snapshot, quote);
    }

    private static DataAuthority.PlayerProfileCommand profileCommand(
        UUID commandId,
        UUID playerId,
        long expectedRevision
    ) {
        return new DataAuthority.PlayerProfileCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "RECORD_PLAYER_LOGIN",
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

    private static final class RecordingSnapshotCacheStore implements AuthoritySnapshotCacheStore {
        private final AuthoritySnapshotCacheStore delegate = new InMemoryAuthoritySnapshotCacheStore();
        private final List<String> events;

        private RecordingSnapshotCacheStore(List<String> events) {
            this.events = events;
        }

        @Override
        public Optional<SnapshotLine<DataAuthority.PlayerProfileSnapshot>> readProfile(String aggregateScope) {
            events.add("cache:readProfile:" + aggregateScope);
            return delegate.readProfile(aggregateScope);
        }

        @Override
        public Optional<SnapshotLine<DataAuthority.PlayerPresenceSnapshot>> readPresence(String aggregateScope) {
            events.add("cache:readPresence:" + aggregateScope);
            return delegate.readPresence(aggregateScope);
        }

        @Override
        public Optional<SnapshotLine<DataAuthority.PlayerRankSnapshot>> readRank(String aggregateScope) {
            events.add("cache:readRank:" + aggregateScope);
            return delegate.readRank(aggregateScope);
        }

        @Override
        public void writeProfile(SnapshotLine<DataAuthority.PlayerProfileSnapshot> line) {
            events.add("cache:writeProfile:" + line.aggregateScope() + ":" + line.revision());
            delegate.writeProfile(line);
        }

        @Override
        public void writePresence(SnapshotLine<DataAuthority.PlayerPresenceSnapshot> line) {
            events.add("cache:writePresence:" + line.aggregateScope() + ":" + line.revision());
            delegate.writePresence(line);
        }

        @Override
        public void writeRank(SnapshotLine<DataAuthority.PlayerRankSnapshot> line) {
            events.add("cache:writeRank:" + line.aggregateScope() + ":" + line.revision());
            delegate.writeRank(line);
        }

        @Override
        public void invalidateProfile(String aggregateScope, long revision, long invalidatedAtEpochMillis) {
            events.add("cache:invalidateProfile:" + aggregateScope + ":" + revision);
            delegate.invalidateProfile(aggregateScope, revision, invalidatedAtEpochMillis);
        }

        @Override
        public void invalidatePresence(String aggregateScope, long revision, long invalidatedAtEpochMillis) {
            events.add("cache:invalidatePresence:" + aggregateScope + ":" + revision);
            delegate.invalidatePresence(aggregateScope, revision, invalidatedAtEpochMillis);
        }

        @Override
        public void invalidateRank(String aggregateScope, long revision, long invalidatedAtEpochMillis) {
            events.add("cache:invalidateRank:" + aggregateScope + ":" + revision);
            delegate.invalidateRank(aggregateScope, revision, invalidatedAtEpochMillis);
        }
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
