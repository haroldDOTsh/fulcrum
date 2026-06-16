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

public final class PlayerProfileContracts {
    public static final ContractName CONTRACT = new ContractName("standard.player-profile.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.player-profile";
    public static final String EVENT_TOPIC = "evt.standard.player-profile";
    public static final String STATE_TOPIC = "state.standard.player-profile";
    public static final String RESPONSE_TOPIC = "rsp.standard.player-profile";
    public static final String EFFECTIVE_PROJECTION = "standard_player_profile_effective";

    private PlayerProfileContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("upsert-profile"),
                        "UpsertPlayerProfile",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("displayName", FieldType.STRING),
                                new FieldDeclaration("observedAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("profile-upserted"),
                        "PlayerProfileUpserted",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("displayName", FieldType.STRING),
                                new FieldDeclaration("observedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "PlayerProfileSnapshot",
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("displayName", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(new ProjectionDeclaration(
                        "effectivePlayerProfileProjection",
                        EFFECTIVE_PROJECTION,
                        List.of(
                                new FieldDeclaration("subjectId", FieldType.STRING),
                                new FieldDeclaration("displayName", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-profile-client"), List.of("standard-profile-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-profile-authority"), List.of("standard-profile-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-profile-authority"), List.of("standard-profile-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-profile-authority"), List.of("standard-profile-client"))));
    }
}
