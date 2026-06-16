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

public final class PunishmentContracts {
    public static final ContractName CONTRACT = new ContractName("standard.punishment.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.punishment";
    public static final String EVENT_TOPIC = "evt.standard.punishment";
    public static final String STATE_TOPIC = "state.standard.punishment";
    public static final String RESPONSE_TOPIC = "rsp.standard.punishment";
    public static final String ACTIVE_PROJECTION = "standard_punishment_active";

    private PunishmentContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("issue-punishment"),
                        "IssuePunishment",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("punishmentId", FieldType.STRING),
                                new FieldDeclaration("reason", FieldType.STRING),
                                new FieldDeclaration("issuedAt", FieldType.INSTANT),
                                new FieldDeclaration("expiresAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("punishment-issued"),
                        "PunishmentIssued",
                        activeFields())),
                Optional.of(new SnapshotDeclaration("ActivePunishmentSnapshot", activeFields())),
                List.of(new ProjectionDeclaration(
                        "activePunishmentProjection",
                        ACTIVE_PROJECTION,
                        activeFields())),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-punishment-client"), List.of("standard-punishment-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-punishment-authority"), List.of("standard-punishment-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-punishment-authority"), List.of("standard-punishment-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-punishment-authority"), List.of("standard-punishment-client"))));
    }

    private static List<FieldDeclaration> activeFields() {
        return List.of(
                new FieldDeclaration("subjectId", FieldType.STRING),
                new FieldDeclaration("punishmentId", FieldType.STRING),
                new FieldDeclaration("reason", FieldType.STRING),
                new FieldDeclaration("issuedBy", FieldType.STRING),
                new FieldDeclaration("issuedAt", FieldType.INSTANT),
                new FieldDeclaration("expiresAt", FieldType.INSTANT),
                new FieldDeclaration("revision", FieldType.LONG));
    }
}
