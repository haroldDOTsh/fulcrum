package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachedAuthorityCommandPortTest {
    @Test
    void repeatedCommandWithSameFingerprintReturnsCachedResult() {
        InMemoryCommandResultCache cache = new InMemoryCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            delegated.incrementAndGet();
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                7L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            ));
        }, cache);

        DataAuthority.PlayerRankCommand command = rankCommand(UUID.randomUUID(), UUID.randomUUID(), "idem-1", "ADMIN");

        DataAuthority.CommandResult first = port.submit(command).toCompletableFuture().join();
        DataAuthority.CommandResult second = port.submit(command).toCompletableFuture().join();

        assertThat(first.accepted()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(delegated).hasValue(1);
        assertThat(cache.entries().get(command.idempotencyKey()).contractFingerprint())
            .isEqualTo(DataAuthorityCommandContracts.fingerprint());
    }

    @Test
    void staleContractFingerprintDoesNotServeCachedResult() {
        InMemoryCommandResultCache cache = new InMemoryCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            delegated.incrementAndGet();
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                7L,
                DataAuthority.RejectionReason.NONE,
                "accepted-current-contract"
            ));
        }, cache);

        DataAuthority.PlayerRankCommand command = rankCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "idem-stale-contract",
            "ADMIN"
        );
        cache.write(new CachedAuthorityCommandPort.CachedCommandResult(
            command.idempotencyKey(),
            AuthorityCommandFingerprints.fingerprint(command).commandFingerprint(),
            "old-contract-fingerprint",
            new DataAuthority.CommandResult(
                command.commandId(),
                true,
                3L,
                DataAuthority.RejectionReason.NONE,
                "accepted-old-contract"
            )
        ));

        DataAuthority.CommandResult result = port.submit(command).toCompletableFuture().join();

        assertThat(result.message()).isEqualTo("accepted-current-contract");
        assertThat(delegated).hasValue(1);
        assertThat(cache.entries().get(command.idempotencyKey()).contractFingerprint())
            .isEqualTo(DataAuthorityCommandContracts.fingerprint());
        assertThat(cache.entries().get(command.idempotencyKey()).result().message())
            .isEqualTo("accepted-current-contract");
    }

    @Test
    void cacheReadFailureDelegatesToDurableAuthorityAndRefreshesCache() {
        ReadFailingOnceCommandResultCache cache = new ReadFailingOnceCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            delegated.incrementAndGet();
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                7L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            ));
        }, cache);

        DataAuthority.PlayerRankCommand command = rankCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "idem-cache-read-failure",
            "ADMIN"
        );

        DataAuthority.CommandResult first = port.submit(command).toCompletableFuture().join();
        DataAuthority.CommandResult second = port.submit(command).toCompletableFuture().join();

        assertThat(first.accepted()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(delegated).hasValue(1);
        assertThat(cache.entries().get(command.idempotencyKey()).contractFingerprint())
            .isEqualTo(DataAuthorityCommandContracts.fingerprint());
    }

    @Test
    void cacheWriteFailureStillReturnsDurableResultAndDoesNotPoisonInFlight() {
        WriteFailingCommandResultCache cache = new WriteFailingCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            delegated.incrementAndGet();
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                7L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            ));
        }, cache);

        DataAuthority.PlayerRankCommand command = rankCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "idem-cache-write-failure",
            "ADMIN"
        );

        DataAuthority.CommandResult first = port.submit(command).toCompletableFuture().join();
        DataAuthority.CommandResult second = port.submit(command).toCompletableFuture().join();

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(delegated).hasValue(2);
        assertThat(cache.writes()).hasValue(2);
    }

    @Test
    void concurrentRepeatedCommandSharesInFlightResult() {
        InMemoryCommandResultCache cache = new InMemoryCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CompletableFuture<DataAuthority.CommandResult> delegateResult = new CompletableFuture<>();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            delegated.incrementAndGet();
            return delegateResult;
        }, cache);

        DataAuthority.PlayerRankCommand command = rankCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "idem-in-flight",
            "ADMIN"
        );

        CompletableFuture<DataAuthority.CommandResult> first = port.submit(command).toCompletableFuture();
        CompletableFuture<DataAuthority.CommandResult> second = port.submit(command).toCompletableFuture();

        assertThat(delegated).hasValue(1);
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();

        DataAuthority.CommandResult result = new DataAuthority.CommandResult(
            command.commandId(),
            true,
            8L,
            DataAuthority.RejectionReason.NONE,
            "accepted"
        );
        delegateResult.complete(result);

        assertThat(first.join()).isEqualTo(result);
        assertThat(second.join()).isEqualTo(result);
        assertThat(cache.entries()).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentFingerprintDelegatesToDurableAuthority() {
        InMemoryCommandResultCache cache = new InMemoryCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            int call = delegated.incrementAndGet();
            if (call == 1) {
                return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                    command.commandId(),
                    true,
                    3L,
                    DataAuthority.RejectionReason.NONE,
                    "accepted"
                ));
            }
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT,
                "Idempotency key was already used for different command material"
            ));
        }, cache);

        UUID playerId = UUID.randomUUID();
        String idempotencyKey = "idem-conflict";

        DataAuthority.CommandResult first = port.submit(rankCommand(
            UUID.randomUUID(),
            playerId,
            idempotencyKey,
            "ADMIN"
        )).toCompletableFuture().join();
        DataAuthority.CommandResult second = port.submit(rankCommand(
            UUID.randomUUID(),
            playerId,
            idempotencyKey,
            "MODERATOR"
        )).toCompletableFuture().join();

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isFalse();
        assertThat(second.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT);
        assertThat(delegated).hasValue(2);
        assertThat(cache.entries()).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyWithNewCommandIdDelegatesToDurableAuthority() {
        InMemoryCommandResultCache cache = new InMemoryCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            delegated.incrementAndGet();
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                3L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            ));
        }, cache);

        UUID playerId = UUID.randomUUID();
        String idempotencyKey = "idem-command-id-conflict";

        port.submit(rankCommand(
            UUID.randomUUID(),
            playerId,
            idempotencyKey,
            "ADMIN"
        )).toCompletableFuture().join();
        port.submit(rankCommand(
            UUID.randomUUID(),
            playerId,
            idempotencyKey,
            "ADMIN"
        )).toCompletableFuture().join();

        assertThat(delegated).hasValue(2);
        assertThat(cache.entries()).hasSize(1);
    }

    @Test
    void concurrentDifferentFingerprintWaitsForFirstCommandBeforeDelegating() {
        InMemoryCommandResultCache cache = new InMemoryCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CompletableFuture<DataAuthority.CommandResult> firstDelegateResult = new CompletableFuture<>();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            int call = delegated.incrementAndGet();
            if (call == 1) {
                return firstDelegateResult;
            }
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT,
                "Idempotency key was already used for different command material"
            ));
        }, cache);

        UUID playerId = UUID.randomUUID();
        String idempotencyKey = "idem-in-flight-conflict";
        DataAuthority.PlayerRankCommand firstCommand = rankCommand(
            UUID.randomUUID(),
            playerId,
            idempotencyKey,
            "ADMIN"
        );
        DataAuthority.PlayerRankCommand secondCommand = rankCommand(
            UUID.randomUUID(),
            playerId,
            idempotencyKey,
            "MODERATOR"
        );

        CompletableFuture<DataAuthority.CommandResult> first = port.submit(firstCommand).toCompletableFuture();
        CompletableFuture<DataAuthority.CommandResult> second = port.submit(secondCommand).toCompletableFuture();

        assertThat(delegated).hasValue(1);
        assertThat(second).isNotDone();

        firstDelegateResult.complete(new DataAuthority.CommandResult(
            firstCommand.commandId(),
            true,
            8L,
            DataAuthority.RejectionReason.NONE,
            "accepted"
        ));

        assertThat(first.join().accepted()).isTrue();
        assertThat(second.join().rejectionReason()).isEqualTo(DataAuthority.RejectionReason.IDEMPOTENCY_CONFLICT);
        assertThat(delegated).hasValue(2);
    }

    @Test
    void storeUnavailableResultIsNotCached() {
        InMemoryCommandResultCache cache = new InMemoryCommandResultCache();
        AtomicInteger delegated = new AtomicInteger();
        CachedAuthorityCommandPort port = new CachedAuthorityCommandPort(command -> {
            delegated.incrementAndGet();
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                false,
                command.expectedRevision(),
                DataAuthority.RejectionReason.STORE_UNAVAILABLE,
                "postgres unavailable"
            ));
        }, cache);

        DataAuthority.PlayerRankCommand command = rankCommand(UUID.randomUUID(), UUID.randomUUID(), "idem-down", "ADMIN");

        port.submit(command).toCompletableFuture().join();
        port.submit(command).toCompletableFuture().join();

        assertThat(delegated).hasValue(2);
        assertThat(cache.entries()).isEmpty();
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        UUID commandId,
        UUID playerId,
        String idempotencyKey,
        String rank
    ) {
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "GRANT_RANK",
                "rank-service",
                "rank:player:" + playerId,
                idempotencyKey,
                System.currentTimeMillis() + 1_000L,
                "",
                DataAuthority.ANY_REVISION,
                new DataAuthority.CommandProvenance(
                    "paper-1",
                    "messagebus:paper-1->registry-service",
                    "message-bus-provider",
                    DataAuthority.COMMAND_SCHEMA_VERSION,
                    "node:paper-1"
                )
            ),
            playerId,
            rank,
            List.of("DEFAULT", rank)
        );
    }

    private static class InMemoryCommandResultCache implements CachedAuthorityCommandPort.CommandResultCache {
        private final Map<String, CachedAuthorityCommandPort.CachedCommandResult> entries = new ConcurrentHashMap<>();

        @Override
        public Optional<CachedAuthorityCommandPort.CachedCommandResult> read(String idempotencyKey) {
            return Optional.ofNullable(entries.get(idempotencyKey));
        }

        @Override
        public void write(CachedAuthorityCommandPort.CachedCommandResult result) {
            entries.compute(result.idempotencyKey(), (key, existing) ->
                existing == null || existing.commandFingerprint().equals(result.commandFingerprint())
                    ? result
                    : existing);
        }

        Map<String, CachedAuthorityCommandPort.CachedCommandResult> entries() {
            return entries;
        }
    }

    private static final class ReadFailingOnceCommandResultCache extends InMemoryCommandResultCache {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public Optional<CachedAuthorityCommandPort.CachedCommandResult> read(String idempotencyKey) {
            if (reads.incrementAndGet() == 1) {
                throw new IllegalStateException("cache read failed");
            }
            return super.read(idempotencyKey);
        }
    }

    private static final class WriteFailingCommandResultCache implements CachedAuthorityCommandPort.CommandResultCache {
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public Optional<CachedAuthorityCommandPort.CachedCommandResult> read(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public void write(CachedAuthorityCommandPort.CachedCommandResult result) {
            writes.incrementAndGet();
            throw new IllegalStateException("cache write failed");
        }

        private AtomicInteger writes() {
            return writes;
        }
    }
}
