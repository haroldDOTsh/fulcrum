package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

/**
 * Data Authority command client backed by the durable authority log.
 */
public final class AuthorityLogDataAuthorityClient implements DataAuthority.CommandPort,
    DataAuthority.CommandSubmissionPort {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(25);
    private static final int DEFAULT_POLL_BATCH_SIZE = 64;

    private final AuthorityLog log;
    private final Duration timeout;
    private final Duration pollInterval;
    private final int pollBatchSize;
    private final DataAuthority.CommandProvenance transportProvenance;

    public AuthorityLogDataAuthorityClient(InMemoryAuthorityLog log) {
        this((AuthorityLog) log, DEFAULT_TIMEOUT);
    }

    public AuthorityLogDataAuthorityClient(InMemoryAuthorityLog log, Duration timeout) {
        this((AuthorityLog) log, timeout);
    }

    public AuthorityLogDataAuthorityClient(
        InMemoryAuthorityLog log,
        Duration timeout,
        DataAuthority.CommandProvenance transportProvenance
    ) {
        this((AuthorityLog) log, timeout, transportProvenance);
    }

    public AuthorityLogDataAuthorityClient(KafkaAuthorityLog log) {
        this((AuthorityLog) log, DEFAULT_TIMEOUT);
    }

    public AuthorityLogDataAuthorityClient(KafkaAuthorityLog log, Duration timeout) {
        this((AuthorityLog) log, timeout);
    }

    public AuthorityLogDataAuthorityClient(
        KafkaAuthorityLog log,
        Duration timeout,
        DataAuthority.CommandProvenance transportProvenance
    ) {
        this((AuthorityLog) log, timeout, transportProvenance);
    }

    AuthorityLogDataAuthorityClient(AuthorityLog log, Duration timeout) {
        this(log, timeout, DataAuthority.CommandProvenance.unknown());
    }

    AuthorityLogDataAuthorityClient(
        AuthorityLog log,
        Duration timeout,
        DataAuthority.CommandProvenance transportProvenance
    ) {
        this(log, timeout, DEFAULT_POLL_INTERVAL, DEFAULT_POLL_BATCH_SIZE, transportProvenance);
    }

    AuthorityLogDataAuthorityClient(
        AuthorityLog log,
        Duration timeout,
        Duration pollInterval,
        int pollBatchSize
    ) {
        this(log, timeout, pollInterval, pollBatchSize, DataAuthority.CommandProvenance.unknown());
    }

    AuthorityLogDataAuthorityClient(
        AuthorityLog log,
        Duration timeout,
        Duration pollInterval,
        int pollBatchSize,
        DataAuthority.CommandProvenance transportProvenance
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.pollInterval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
        this.pollBatchSize = Math.max(1, pollBatchSize);
        this.transportProvenance = transportProvenance == null
            ? DataAuthority.CommandProvenance.unknown()
            : transportProvenance;
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        DataAuthority.AuthorityCommand submitted = withTransportProvenance(command);
        AuthorityLogRecord commandRecord;
        try {
            commandRecord = AuthorityLogFrames.appendCommand(log, submitted);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(AuthorityLogFrames.storeUnavailable(submitted, exception));
        }
        return CompletableFuture.supplyAsync(() -> awaitResponse(submitted, commandRecord));
    }

    @Override
    public CompletionStage<DataAuthority.CommandSubmissionReceipt> submitDurable(
        DataAuthority.AuthorityCommand command
    ) {
        DataAuthority.AuthorityCommand submitted = withTransportProvenance(command);
        try {
            AuthorityLogRecord commandRecord = AuthorityLogFrames.appendCommand(log, submitted);
            return CompletableFuture.completedFuture(receipt(submitted, commandRecord));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private DataAuthority.AuthorityCommand withTransportProvenance(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        if (DataAuthority.CommandProvenance.unknown().equals(transportProvenance)) {
            return command;
        }
        AuthorityCommandFrame frame = AuthorityCommandFrame.fromCommand(command);
        String actorId = AuthorityPrincipals.known(transportProvenance.verifiedPrincipal())
            ? transportProvenance.verifiedPrincipal()
            : frame.actorId();
        return new AuthorityCommandFrame(
            frame.commandId(),
            frame.declarationId(),
            actorId,
            frame.scope(),
            frame.idempotencyKey(),
            frame.deadlineEpochMillis(),
            frame.fencingToken(),
            frame.expectedRevision(),
            frame.schemaVersion(),
            frame.route(),
            transportProvenance,
            frame.payload()
        ).toCommand();
    }

    private DataAuthority.CommandResult awaitResponse(
        DataAuthority.AuthorityCommand command,
        AuthorityLogRecord commandRecord
    ) {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long afterOffset = -1L;
        while (System.nanoTime() <= deadlineNanos) {
            for (AuthorityLogRecord response : log.records(
                route.responseTopic(),
                commandRecord.partition(),
                afterOffset,
                pollBatchSize
            )) {
                afterOffset = Math.max(afterOffset, response.offset());
                if (command.commandId().equals(commandId(response))) {
                    return commandResult(command, response);
                }
            }
            if (!sleep()) {
                return AuthorityLogFrames.storeUnavailable(
                    command,
                    new InterruptedException("Interrupted while waiting for authority response")
                );
            }
        }
        return AuthorityLogFrames.storeUnavailable(
            command,
            new TimeoutException("Timed out waiting for authority response on " + route.responseTopic())
        );
    }

    private boolean sleep() {
        try {
            Thread.sleep(Math.max(1L, pollInterval.toMillis()));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static DataAuthority.CommandResult commandResult(
        DataAuthority.AuthorityCommand command,
        AuthorityLogRecord response
    ) {
        if (response.kind() != AuthorityLogTopicKind.RESPONSE) {
            throw new IllegalStateException("Authority log client expected a RESPONSE record");
        }
        Map<String, Object> payload = response.payload();
        DataAuthority.CommandResult result = new DataAuthority.CommandResult(
            uuid(payload.get("commandId")),
            booleanValue(payload.get("accepted"), false),
            longValue(payload.get("revision"), 0L),
            rejectionReason(payload.get("rejectionReason")),
            string(payload.get("message")),
            DataAuthority.CommandSettlement.fromPayload(
                mapValue(payload.get("settlement")),
                DataAuthority.CommandSettlement.unsettled(longValue(payload.get("revision"), 0L))
            ),
            DataAuthority.CommandRefusalReceipt.fromPayload(mapValue(payload.get("refusalReceipt")), null)
        );
        AuthorityCommandManifest.validateResult(command, result);
        return result;
    }

    private static DataAuthority.CommandSubmissionReceipt receipt(
        DataAuthority.AuthorityCommand command,
        AuthorityLogRecord commandRecord
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
            commandRecord.partition(),
            commandRecord.offset(),
            commandRecord.appendedAtEpochMillis(),
            command.provenance()
        );
    }

    private static UUID commandId(AuthorityLogRecord response) {
        Object rawCommandId = response.payload().get("commandId");
        if (rawCommandId == null) {
            return null;
        }
        return uuid(rawCommandId);
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("UUID value is required");
        }
        return UUID.fromString(value.toString());
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static DataAuthority.RejectionReason rejectionReason(Object value) {
        if (value == null || value.toString().isBlank()) {
            return DataAuthority.RejectionReason.NONE;
        }
        return DataAuthority.RejectionReason.valueOf(value.toString());
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
