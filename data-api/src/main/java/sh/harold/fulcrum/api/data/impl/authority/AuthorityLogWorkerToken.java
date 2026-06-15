package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record AuthorityLogWorkerToken(
    String commandTopic,
    String commandKey,
    int commandPartition,
    long commandOffset,
    String commandId,
    String commandFingerprint,
    AuthorityWriterClaim writerClaim
) {
    AuthorityLogWorkerToken {
        commandTopic = requireText(commandTopic, "commandTopic");
        commandKey = requireText(commandKey, "commandKey");
        if (commandPartition < 0) {
            throw new IllegalArgumentException("commandPartition must be non-negative");
        }
        if (commandOffset < 0L) {
            throw new IllegalArgumentException("commandOffset must be non-negative");
        }
        commandId = requireText(commandId, "commandId");
        commandFingerprint = requireText(commandFingerprint, "commandFingerprint");
    }

    static AuthorityLogWorkerToken fromCommandRecord(
        AuthorityLogRecord record,
        DataAuthority.AuthorityCommand command
    ) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(command, "command");
        if (record.kind() != AuthorityLogTopicKind.COMMAND) {
            throw new IllegalArgumentException("Authority worker token requires a COMMAND record");
        }
        String payloadCommandId = string(record.payload().get("commandId"));
        if (!command.commandId().toString().equals(payloadCommandId)) {
            throw new IllegalArgumentException("Authority worker token command id does not match command");
        }
        return new AuthorityLogWorkerToken(
            record.topic(),
            record.key(),
            record.partition(),
            record.offset(),
            payloadCommandId,
            string(record.payload().get("commandFingerprint")),
            null
        );
    }

    AuthorityLogWorkerToken withWriterClaim(AuthorityWriterClaim claim) {
        return new AuthorityLogWorkerToken(
            commandTopic,
            commandKey,
            commandPartition,
            commandOffset,
            commandId,
            commandFingerprint,
            Objects.requireNonNull(claim, "claim")
        );
    }

    Map<String, Object> payload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceCommandTopic", commandTopic);
        values.put("sourceCommandKey", commandKey);
        values.put("sourceCommandPartition", commandPartition);
        values.put("sourceCommandOffset", commandOffset);
        values.put("sourceCommandId", commandId);
        values.put("sourceCommandFingerprint", commandFingerprint);
        if (writerClaim != null) {
            values.put("writerClaimId", writerClaim.claimId().toString());
            values.put("writerClaimOwnerNode", writerClaim.ownerNode());
            values.put("writerClaimEpoch", writerClaim.epoch());
            values.put("writerClaimPartitionKey", writerClaim.partitionKey());
            values.put("writerClaimFingerprint", writerClaim.claimFingerprint());
        }
        return Map.copyOf(values);
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
