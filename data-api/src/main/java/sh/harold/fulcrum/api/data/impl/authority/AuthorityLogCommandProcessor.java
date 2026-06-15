package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Applies already-durable authority command records and emits terminal log frames.
 */
public final class AuthorityLogCommandProcessor {
    private final AuthorityLog log;
    private final DataAuthority.CommandPort delegate;

    public AuthorityLogCommandProcessor(InMemoryAuthorityLog log, DataAuthority.CommandPort delegate) {
        this((AuthorityLog) log, delegate);
    }

    public AuthorityLogCommandProcessor(KafkaAuthorityLog log, DataAuthority.CommandPort delegate) {
        this((AuthorityLog) log, delegate);
    }

    AuthorityLogCommandProcessor(AuthorityLog log, DataAuthority.CommandPort delegate) {
        this.log = Objects.requireNonNull(log, "log");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public CompletionStage<ProcessingResult> process(AuthorityLogRecord commandRecord) {
        Objects.requireNonNull(commandRecord, "commandRecord");
        return CompletableFuture.failedFuture(new IllegalArgumentException(
            "Authority command processing requires a writer claim"
        ));
    }

    public CompletionStage<ProcessingResult> process(
        AuthorityLogRecord commandRecord,
        AuthorityWriterClaim writerClaim
    ) {
        Objects.requireNonNull(commandRecord, "commandRecord");
        DataAuthority.AuthorityCommand decodedCommand;
        try {
            decodedCommand = command(commandRecord);
            if (writerClaim != null) {
                validateClaim(decodedCommand, writerClaim);
                decodedCommand = AuthorityFencingCommandPort.stampWithClaim(decodedCommand, writerClaim);
            }
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        final DataAuthority.AuthorityCommand command = decodedCommand;
        AuthorityLogWorkerToken decodedWorkerToken = AuthorityLogWorkerToken.fromCommandRecord(commandRecord, command);
        if (writerClaim != null) {
            decodedWorkerToken = decodedWorkerToken.withWriterClaim(writerClaim);
        }
        final AuthorityLogWorkerToken workerToken = decodedWorkerToken;

        CompletionStage<DataAuthority.CommandResult> delegated;
        try {
            delegated = delegate.submit(command);
        } catch (RuntimeException exception) {
            DataAuthority.CommandResult failed = AuthorityLogFrames.storeUnavailable(command, exception);
            return CompletableFuture.completedFuture(terminal(commandRecord, command, failed, workerToken));
        }

        return delegated.handle((result, failure) -> {
            DataAuthority.CommandResult terminal = failure == null
                ? result
                : AuthorityLogFrames.storeUnavailable(command, failure);
            return terminal(commandRecord, command, terminal, workerToken);
        });
    }

    private static void validateClaim(DataAuthority.AuthorityCommand command, AuthorityWriterClaim claim) {
        AuthorityWriteCustody custody = AuthorityWriteCustody.fromCommand(command);
        requireEqual("claim domain", custody.commandDomain(), claim.commandDomain());
        requireEqual("claim command topic", custody.commandTopic(), claim.commandTopic());
        requireEqual("claim partition key", custody.ownershipPartitionKey(), claim.partitionKey());
        if (claim.ownerNode() == null || claim.ownerNode().isBlank()) {
            throw new IllegalArgumentException("Authority command worker claim owner is required");
        }
        if (claim.epoch() <= 0L) {
            throw new IllegalArgumentException("Authority command worker claim epoch must be positive");
        }
    }

    private ProcessingResult terminal(
        AuthorityLogRecord commandRecord,
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result,
        AuthorityLogWorkerToken workerToken
    ) {
        return new ProcessingResult(
            commandRecord,
            AuthorityLogFrames.appendTerminal(log, command, result, workerToken)
        );
    }

    private static DataAuthority.AuthorityCommand command(AuthorityLogRecord commandRecord) {
        if (commandRecord.kind() != AuthorityLogTopicKind.COMMAND) {
            throw new IllegalArgumentException("Authority log worker can only process COMMAND records");
        }
        Map<String, Object> manifest = map(commandRecord.payload().get("manifest"), "manifest");
        Map<String, Object> payload = map(commandRecord.payload().get("payload"), "payload");
        AuthorityCommandFrame frame = AuthorityCommandFrame.fromPayloads(manifest, payload);
        DataAuthority.AuthorityCommand command = frame.toCommand();
        validateLineage(commandRecord, frame, command);
        return command;
    }

    private static void validateLineage(
        AuthorityLogRecord commandRecord,
        AuthorityCommandFrame frame,
        DataAuthority.AuthorityCommand command
    ) {
        AuthorityCommandRoute route = frame.route();
        requireEqual("topic", route.commandTopic(), commandRecord.topic());
        requireEqual("key", route.partitionKey(), commandRecord.key());
        int expectedPartition = AuthorityLogTopology.partition(route);
        if (expectedPartition != commandRecord.partition()) {
            throw new IllegalArgumentException(
                "Authority command record partition " + commandRecord.partition()
                    + " does not match route partition " + expectedPartition
            );
        }
        requireEqual("commandId", command.commandId().toString(), string(commandRecord.payload().get("commandId")));
        requireEqual("commandType", command.type().name(), string(commandRecord.payload().get("commandType")));
        requireEqual("aggregateScope", command.scope(), string(commandRecord.payload().get("aggregateScope")));
        requireEqual(
            "contractFingerprint",
            DataAuthorityCommandContracts.fingerprint(),
            string(commandRecord.payload().get("contractFingerprint"))
        );
        requireEqual(
            "routeManifestFingerprint",
            DataAuthorityCommandContracts.routeManifestFingerprint(),
            string(commandRecord.payload().get("routeManifestFingerprint"))
        );
        AuthorityCommandFingerprints.Fingerprint fingerprint = AuthorityCommandFingerprints.fingerprint(command);
        requireEqual("payloadHash", fingerprint.payloadHash(), string(commandRecord.payload().get("payloadHash")));
        requireEqual(
            "commandFingerprint",
            fingerprint.commandFingerprint(),
            string(commandRecord.payload().get("commandFingerprint"))
        );
    }

    private static void requireEqual(String field, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(
                "Authority command record " + field + " " + actual + " does not match " + expected
            );
        }
    }

    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Authority command record is missing " + field + " payload");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                result.put(key.toString(), item);
            }
        });
        return Map.copyOf(result);
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    public record ProcessingResult(
        AuthorityLogRecord commandRecord,
        DataAuthority.CommandResult commandResult
    ) {
        public ProcessingResult {
            commandRecord = Objects.requireNonNull(commandRecord, "commandRecord");
            commandResult = Objects.requireNonNull(commandResult, "commandResult");
        }
    }
}
