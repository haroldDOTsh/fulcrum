package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.paper.PaperRewardSink;
import sh.harold.fulcrum.host.paper.PaperSessionRewardReport;
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.StatsContracts;
import sh.harold.fulcrum.standard.economy.EconomyAuthority;
import sh.harold.fulcrum.standard.economy.PostLedgerEntry;
import sh.harold.fulcrum.standard.stats.RecordStatDelta;
import sh.harold.fulcrum.standard.stats.StatsAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

final class PaperRewardCommandPublisher implements PaperRewardSink {
    private static final long AUTHORITY_FENCING_EPOCH = 1;
    private static final long STATS_DELTA = 1;

    private final HostSecurityContext securityContext;
    private final Producer<String, String> producer;
    private final String economyCommandTopic;
    private final String statsCommandTopic;
    private final ExperienceId experienceId;
    private final String currencyKey;
    private final long rewardAmountMinorUnits;
    private final String statKey;

    PaperRewardCommandPublisher(
            HostSecurityContext securityContext,
            Producer<String, String> producer,
            String economyCommandTopic,
            String statsCommandTopic,
            ExperienceId experienceId,
            String currencyKey,
            long rewardAmountMinorUnits,
            String statKey) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.economyCommandTopic = requireNonBlank(economyCommandTopic, "economyCommandTopic");
        this.statsCommandTopic = requireNonBlank(statsCommandTopic, "statsCommandTopic");
        this.experienceId = Objects.requireNonNull(experienceId, "experienceId");
        this.currencyKey = requireNonBlank(currencyKey, "currencyKey");
        this.rewardAmountMinorUnits = rewardAmountMinorUnits;
        this.statKey = requireNonBlank(statKey, "statKey");
        if (rewardAmountMinorUnits <= 0) {
            throw new IllegalArgumentException("rewardAmountMinorUnits must be positive");
        }
    }

    @Override
    public void publish(PaperSessionRewardReport report) {
        Objects.requireNonNull(report, "report");
        requireProduceGrant(economyCommandTopic);
        requireProduceGrant(statsCommandTopic);
        publishEconomyCommand(economyCommand(report));
        publishStatsCommand(statsCommand(report));
    }

    private AuthorityCommand<PostLedgerEntry> economyCommand(PaperSessionRewardReport report) {
        PostLedgerEntry payload = new PostLedgerEntry(
                report.subjectId(),
                currencyKey,
                rewardAmountMinorUnits,
                "session-reward:" + report.sessionId().value(),
                report.occurredAt(),
                0);
        PrincipalId principalId = securityContext.identity().principalId();
        CommandEnvelope<PostLedgerEntry> envelope = new CommandEnvelope<>(
                commandId("economy", report),
                idempotencyKey("economy", report),
                principalId,
                EconomyAuthority.aggregateId(payload.accountId()),
                EconomyContracts.CONTRACT,
                new CommandName(StandardCapabilityAuthorityWireCodec.POST_LEDGER_ENTRY_COMMAND),
                report.traceEnvelope(),
                deadline(report.occurredAt()),
                payload);
        return new AuthorityCommand<>(
                envelope,
                principalId,
                AUTHORITY_FENCING_EPOCH,
                Optional.empty(),
                fingerprint("economy", report, payload.currencyKey(), payload.deltaMinorUnits()),
                report.occurredAt());
    }

    private AuthorityCommand<RecordStatDelta> statsCommand(PaperSessionRewardReport report) {
        RecordStatDelta payload = new RecordStatDelta(
                report.subjectId(),
                experienceId,
                statKey,
                STATS_DELTA,
                report.occurredAt(),
                0);
        PrincipalId principalId = securityContext.identity().principalId();
        CommandEnvelope<RecordStatDelta> envelope = new CommandEnvelope<>(
                commandId("stats", report),
                idempotencyKey("stats", report),
                principalId,
                StatsAuthority.aggregateId(payload.counterId()),
                StatsContracts.CONTRACT,
                new CommandName(StandardCapabilityAuthorityWireCodec.RECORD_STAT_DELTA_COMMAND),
                report.traceEnvelope(),
                deadline(report.occurredAt()),
                payload);
        return new AuthorityCommand<>(
                envelope,
                principalId,
                AUTHORITY_FENCING_EPOCH,
                Optional.empty(),
                fingerprint("stats", report, payload.statKey(), payload.delta()),
                report.occurredAt());
    }

    private void publishEconomyCommand(AuthorityCommand<PostLedgerEntry> command) {
        publish(
                economyCommandTopic,
                command.envelope().aggregateId().value(),
                StandardCapabilityAuthorityWireCodec.encodeEconomyCommand(command));
    }

    private void publishStatsCommand(AuthorityCommand<RecordStatDelta> command) {
        publish(
                statsCommandTopic,
                command.envelope().aggregateId().value(),
                StandardCapabilityAuthorityWireCodec.encodeStatsCommand(command));
    }

    private void publish(String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing Paper reward command to " + topic, exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not publish Paper reward command to " + topic, exception);
        }
    }

    private void requireProduceGrant(String topic) {
        if (!securityContext.credentialScope().permits(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, topic)) {
            throw new SecurityException("Paper Instance is not allowed to produce reward commands to " + topic);
        }
    }

    private static CommandId commandId(String family, PaperSessionRewardReport report) {
        return new CommandId("command-paper-reward-" + family + "-" + suffix(report));
    }

    private static IdempotencyKey idempotencyKey(String family, PaperSessionRewardReport report) {
        return new IdempotencyKey("idem-paper-reward-" + family + "-" + suffix(report));
    }

    private static String suffix(PaperSessionRewardReport report) {
        return compact(report.sessionId().value()) + "-" + compact(report.subjectId().value().toString());
    }

    private static Optional<Instant> deadline(Instant occurredAt) {
        return Optional.of(occurredAt.plusSeconds(30));
    }

    private static String fingerprint(
            String family,
            PaperSessionRewardReport report,
            String key,
            long delta) {
        String value = new StringBuilder()
                .append("family=").append(family).append('\n')
                .append("sessionId=").append(report.sessionId().value()).append('\n')
                .append("routeId=").append(report.routeId().value()).append('\n')
                .append("subjectId=").append(report.subjectId().value()).append('\n')
                .append("key=").append(key).append('\n')
                .append("delta=").append(delta).append('\n')
                .append("occurredAt=").append(report.occurredAt()).append('\n')
                .toString();
        return sha256(value);
    }

    private static String compact(String value) {
        return value.replace("-", "");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
