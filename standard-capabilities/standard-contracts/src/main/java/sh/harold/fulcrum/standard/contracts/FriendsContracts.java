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

public final class FriendsContracts {
    public static final ContractName CONTRACT = new ContractName("standard.friends.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.friends";
    public static final String EVENT_TOPIC = "evt.standard.friends";
    public static final String STATE_TOPIC = "state.standard.friends";
    public static final String RESPONSE_TOPIC = "rsp.standard.friends";
    public static final String CONNECTION_PROJECTION = "standard_friends_connection";
    public static final String SUBJECT_INDEX_PROJECTION = "standard_friends_subject_index";

    private FriendsContracts() {
    }

    public static ContractDeclaration contract() {
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("accept-friend-invite"),
                        "AcceptFriendInvite",
                        List.of(
                                new FieldDeclaration("requesterSubjectId", FieldType.STRING),
                                new FieldDeclaration("accepterSubjectId", FieldType.STRING),
                                new FieldDeclaration("acceptedAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("friend-invite-accepted"),
                        "FriendInviteAccepted",
                        List.of(
                                new FieldDeclaration("connectionId", FieldType.STRING),
                                new FieldDeclaration("requesterSubjectId", FieldType.STRING),
                                new FieldDeclaration("accepterSubjectId", FieldType.STRING),
                                new FieldDeclaration("acceptedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                Optional.of(new SnapshotDeclaration(
                        "FriendConnectionSnapshot",
                        List.of(
                                new FieldDeclaration("connectionId", FieldType.STRING),
                                new FieldDeclaration("subjectOneId", FieldType.STRING),
                                new FieldDeclaration("subjectTwoId", FieldType.STRING),
                                new FieldDeclaration("acceptedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new ProjectionDeclaration(
                                "friendConnectionProjection",
                                CONNECTION_PROJECTION,
                                List.of(
                                        new FieldDeclaration("connectionId", FieldType.STRING),
                                        new FieldDeclaration("subjectOneId", FieldType.STRING),
                                        new FieldDeclaration("subjectTwoId", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG))),
                        new ProjectionDeclaration(
                                "friendSubjectIndexProjection",
                                SUBJECT_INDEX_PROJECTION,
                                List.of(
                                        new FieldDeclaration("subjectId", FieldType.STRING),
                                        new FieldDeclaration("friendSubjectId", FieldType.STRING),
                                        new FieldDeclaration("connectionId", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-friends-client"), List.of("standard-friends-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-friends-authority"), List.of("standard-friends-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-friends-authority"), List.of("standard-friends-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-friends-authority"), List.of("standard-friends-client"))));
    }
}
