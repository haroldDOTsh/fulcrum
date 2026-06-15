package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Records authority command frames to the authority log before and after execution.
 */
public final class AuthorityLogCommandPort implements AuthorityCommandJournal.Recorder {
    private final AuthorityLog log;
    private final AuthorityLogCommandWorker worker;

    /**
     * Creates a command-port decorator backed by the in-memory authority log.
     *
     * @param log authority log
     * @param delegate authority command writer
     */
    public AuthorityLogCommandPort(InMemoryAuthorityLog log, DataAuthority.CommandPort delegate) {
        this(
            (AuthorityLog) log,
            delegate,
            new InMemoryPartitionEpochStore(),
            "authority-log-worker"
        );
    }

    /**
     * Creates a command-port decorator backed by Kafka-compatible authority topics.
     *
     * @param log Kafka authority log
     * @param delegate authority command writer
     */
    public AuthorityLogCommandPort(KafkaAuthorityLog log, DataAuthority.CommandPort delegate) {
        this(
            (AuthorityLog) log,
            delegate,
            new InMemoryPartitionEpochStore(),
            "authority-log-worker"
        );
    }

    public AuthorityLogCommandPort(
        InMemoryAuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode
    ) {
        this((AuthorityLog) log, delegate, epochStore, ownerNode);
    }

    public AuthorityLogCommandPort(
        KafkaAuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode
    ) {
        this((AuthorityLog) log, delegate, epochStore, ownerNode);
    }

    AuthorityLogCommandPort(AuthorityLog log, DataAuthority.CommandPort delegate) {
        this(
            log,
            delegate,
            new InMemoryPartitionEpochStore(),
            "authority-log-worker"
        );
    }

    AuthorityLogCommandPort(
        AuthorityLog log,
        DataAuthority.CommandPort delegate,
        AuthorityFencingCommandPort.PartitionEpochStore epochStore,
        String ownerNode
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.worker = new AuthorityLogCommandWorker(
            log,
            Objects.requireNonNull(delegate, "delegate"),
            Objects.requireNonNull(epochStore, "epochStore"),
            ownerNode
        );
    }

    @Override
    public void validateSchema() {
        log.validateSchema();
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        AuthorityLogRecord commandRecord;
        try {
            commandRecord = AuthorityLogFrames.appendCommand(log, command);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(AuthorityLogFrames.storeUnavailable(command, exception));
        }

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        return worker.processPartition(route.domain(), commandRecord.partition(), commandRecord.offset() - 1L, 1)
            .handle((processed, failure) -> {
                if (failure == null) {
                    return resultFor(commandRecord, processed);
                }
                DataAuthority.CommandResult failed = AuthorityLogFrames.storeUnavailable(command, failure);
                return AuthorityLogFrames.appendTerminal(log, command, failed);
            });
    }

    private static DataAuthority.CommandResult resultFor(
        AuthorityLogRecord commandRecord,
        AuthorityLogCommandWorker.PartitionResult processed
    ) {
        return processed.processed().stream()
            .filter(result -> result.commandRecord().topic().equals(commandRecord.topic()))
            .filter(result -> result.commandRecord().partition() == commandRecord.partition())
            .filter(result -> result.commandRecord().offset() == commandRecord.offset())
            .findFirst()
            .map(AuthorityLogCommandProcessor.ProcessingResult::commandResult)
            .orElseThrow(() -> new IllegalStateException(
                "Authority worker did not process command record "
                    + commandRecord.topic() + "-" + commandRecord.partition() + "@" + commandRecord.offset()
            ));
    }

    private static final class InMemoryPartitionEpochStore
        implements AuthorityFencingCommandPort.PartitionEpochStore {
        private final ConcurrentMap<String, AuthorityWriterClaim> claims = new ConcurrentHashMap<>();

        @Override
        public AuthorityWriterClaim claimEpoch(
            String commandDomain,
            String commandTopic,
            String partitionKey,
            String ownerNode
        ) {
            String key = commandDomain + "\n" + partitionKey;
            return claims.compute(key, (ignored, previous) -> {
                long epoch = previous == null
                    ? 1L
                    : ownerNode.equals(previous.ownerNode()) ? previous.epoch() : previous.epoch() + 1L;
                return AuthorityWriterClaim.mint(
                    commandDomain,
                    commandTopic,
                    partitionKey,
                    ownerNode,
                    epoch,
                    previous == null ? null : previous.ownerNode(),
                    previous == null ? 0L : previous.epoch(),
                    Instant.now()
                );
            });
        }
    }
}
