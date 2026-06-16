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

public final class RankContracts {
    public static final ContractName CONTRACT = new ContractName("standard.rank.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.rank";
    public static final String EVENT_TOPIC = "evt.standard.rank";
    public static final String STATE_TOPIC = "state.standard.rank";
    public static final String RESPONSE_TOPIC = "rsp.standard.rank";
    public static final String EFFECTIVE_PROJECTION = "standard_rank_effective";

    private RankContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("grant-rank"),
                        "GrantRank",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("rankKey", FieldType.STRING),
                                new FieldDeclaration("grantedAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("rank-granted"),
                        "RankGranted",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("rankKey", FieldType.STRING),
                                new FieldDeclaration("grantedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "EffectiveRankSnapshot",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("primaryRankKey", FieldType.STRING),
                                new FieldDeclaration("permissions", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(new ProjectionDeclaration(
                        "effectiveRankProjection",
                        EFFECTIVE_PROJECTION,
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("primaryRankKey", FieldType.STRING),
                                new FieldDeclaration("permissions", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-rank-client"), List.of("standard-rank-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-rank-authority"), List.of("standard-rank-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-rank-authority"), List.of("standard-rank-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-rank-authority"), List.of("standard-rank-client"))));
    }
}
