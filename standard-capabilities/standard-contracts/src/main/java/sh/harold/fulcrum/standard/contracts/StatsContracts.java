package sh.harold.fulcrum.standard.contracts;

import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.EventName;
import sh.harold.fulcrum.data.contract.AclRuleDeclaration;
import sh.harold.fulcrum.data.contract.CommandDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.EventDeclaration;
import sh.harold.fulcrum.data.contract.FieldDeclaration;
import sh.harold.fulcrum.data.contract.FieldType;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.SnapshotDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;
import sh.harold.fulcrum.data.contract.TopicFamily;

import java.util.List;
import java.util.Optional;

public final class StatsContracts {
    public static final ContractName CONTRACT = new ContractName("standard.stats.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.stats";
    public static final String EVENT_TOPIC = "evt.standard.stats";
    public static final String STATE_TOPIC = "state.standard.stats";
    public static final String RESPONSE_TOPIC = "rsp.standard.stats";
    public static final String COUNTER_PROJECTION = "standard_stats_counter";
    public static final String EXPERIENCE_COUNTER_PROJECTION = "standard_stats_experience_counter";
    public static final String LEDGER_PROJECTION = "standard_stats_ledger";

    private StatsContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("record-stat-delta"),
                        "RecordStatDelta",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("experienceId", FieldType.STRING),
                                new FieldDeclaration("statKey", FieldType.STRING),
                                new FieldDeclaration("delta", FieldType.LONG),
                                new FieldDeclaration("occurredAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("stat-delta-recorded"),
                        "StatsDeltaRecorded",
                        List.of(
                                new FieldDeclaration("entryId", FieldType.STRING),
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("experienceId", FieldType.STRING),
                                new FieldDeclaration("statKey", FieldType.STRING),
                                new FieldDeclaration("delta", FieldType.LONG),
                                new FieldDeclaration("resultingTotal", FieldType.LONG),
                                new FieldDeclaration("recordedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "StatsCounterSnapshot",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("statKey", FieldType.STRING),
                                new FieldDeclaration("total", FieldType.LONG),
                                new FieldDeclaration("lastEntryId", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new ProjectionDeclaration(
                                "statsCounterProjection",
                                COUNTER_PROJECTION,
                                List.of(
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("statKey", FieldType.STRING),
                                        new FieldDeclaration("total", FieldType.LONG),
                                        new FieldDeclaration("lastEntryId", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "statsExperienceCounterProjection",
                                EXPERIENCE_COUNTER_PROJECTION,
                                List.of(
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("experienceId", FieldType.STRING),
                                        new FieldDeclaration("statKey", FieldType.STRING),
                                        new FieldDeclaration("total", FieldType.LONG),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "statsLedgerProjection",
                                LEDGER_PROJECTION,
                                List.of(
                                        new FieldDeclaration("entryId", FieldType.STRING),
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("experienceId", FieldType.STRING),
                                        new FieldDeclaration("statKey", FieldType.STRING),
                                        new FieldDeclaration("delta", FieldType.LONG),
                                        new FieldDeclaration("resultingTotal", FieldType.LONG),
                                        new FieldDeclaration("recordedAt", FieldType.INSTANT),
                                        new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-stats-client"), List.of("standard-stats-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-stats-authority"), List.of("standard-stats-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-stats-authority"), List.of("standard-stats-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-stats-authority"), List.of("standard-stats-client"))));
    }
}
