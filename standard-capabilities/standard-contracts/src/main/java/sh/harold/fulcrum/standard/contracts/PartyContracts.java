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

public final class PartyContracts {
    public static final ContractName CONTRACT = new ContractName("standard.party.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.party";
    public static final String EVENT_TOPIC = "evt.standard.party";
    public static final String STATE_TOPIC = "state.standard.party";
    public static final String RESPONSE_TOPIC = "rsp.standard.party";
    public static final String ROSTER_PROJECTION = "standard_party_roster";
    public static final String SUBJECT_INDEX_PROJECTION = "standard_party_subject_index";

    private PartyContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("form-party"),
                        "FormParty",
                        List.of(
                                new FieldDeclaration("partyId", FieldType.STRING),
                                new FieldDeclaration("leaderSubjectId", FieldType.STRING),
                                new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                new FieldDeclaration("formedAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("party-formed"),
                        "PartyFormed",
                        List.of(
                                new FieldDeclaration("partyId", FieldType.STRING),
                                new FieldDeclaration("leaderSubjectId", FieldType.STRING),
                                new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "PartyRosterSnapshot",
                        List.of(
                                new FieldDeclaration("partyId", FieldType.STRING),
                                new FieldDeclaration("leaderSubjectId", FieldType.STRING),
                                new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new ProjectionDeclaration(
                                "partyRosterProjection",
                                ROSTER_PROJECTION,
                                List.of(
                                        new FieldDeclaration("partyId", FieldType.STRING),
                                        new FieldDeclaration("leaderSubjectId", FieldType.STRING),
                                        new FieldDeclaration("memberSubjectIds", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "partySubjectIndexProjection",
                                SUBJECT_INDEX_PROJECTION,
                                List.of(
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("partyId", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-party-client"), List.of("standard-party-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-party-authority"), List.of("standard-party-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-party-authority"), List.of("standard-party-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-party-authority"), List.of("standard-party-client"))));
    }
}
