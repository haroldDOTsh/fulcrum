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

public final class RealmContracts {
    public static final ContractName CONTRACT = new ContractName("standard.realm.v1");
    public static final String COMMAND_TOPIC = "cmd.standard.realm";
    public static final String EVENT_TOPIC = "evt.standard.realm";
    public static final String STATE_TOPIC = "state.standard.realm";
    public static final String RESPONSE_TOPIC = "rsp.standard.realm";
    public static final String SNAPSHOT_METADATA_PROJECTION = "standard_realm_snapshot_metadata";

    private RealmContracts() {
    }

    public static ContractDeclaration contract() {
        List<FieldDeclaration> snapshotFields = List.of(
                new FieldDeclaration("realmId", FieldType.STRING),
                new FieldDeclaration("snapshotId", FieldType.STRING),
                new FieldDeclaration("corpusId", FieldType.STRING),
                new FieldDeclaration("parentSnapshotId", FieldType.STRING),
                new FieldDeclaration("artifactId", FieldType.STRING),
                new FieldDeclaration("artifactDigest", FieldType.STRING),
                new FieldDeclaration("objectAddress", FieldType.STRING),
                new FieldDeclaration("stateCompatibilityVersion", FieldType.STRING),
                new FieldDeclaration("byteLength", FieldType.LONG),
                new FieldDeclaration("capturedAt", FieldType.INSTANT),
                new FieldDeclaration("revision", FieldType.LONG));
        return new ContractDeclaration(
                CONTRACT,
                List.of(new CommandDeclaration(
                        new CommandName("register-realm-snapshot"),
                        "RegisterRealmSnapshot",
                        List.of(
                                new FieldDeclaration("realmId", FieldType.STRING),
                                new FieldDeclaration("snapshotId", FieldType.STRING),
                                new FieldDeclaration("corpusId", FieldType.STRING),
                                new FieldDeclaration("parentSnapshotId", FieldType.STRING),
                                new FieldDeclaration("artifactId", FieldType.STRING),
                                new FieldDeclaration("artifactDigest", FieldType.STRING),
                                new FieldDeclaration("objectAddress", FieldType.STRING),
                                new FieldDeclaration("stateCompatibilityVersion", FieldType.STRING),
                                new FieldDeclaration("byteLength", FieldType.LONG),
                                new FieldDeclaration("capturedAt", FieldType.INSTANT),
                                new FieldDeclaration("expectedRevision", FieldType.LONG)),
                        true)),
                List.of(new EventDeclaration(
                        new EventName("realm-snapshot-registered"),
                        "RealmSnapshotRegistered",
                        snapshotFields)),
                Optional.of(new SnapshotDeclaration("RealmSnapshotMetadata", snapshotFields)),
                List.of(new ProjectionDeclaration(
                        "realmSnapshotMetadataProjection",
                        SNAPSHOT_METADATA_PROJECTION,
                        snapshotFields)),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration(STATE_TOPIC, TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("standard-realm-client"), List.of("standard-realm-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("standard-realm-authority"), List.of("standard-realm-projection")),
                        new AclRuleDeclaration(STATE_TOPIC, List.of("standard-realm-authority"), List.of("standard-realm-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("standard-realm-authority"), List.of("standard-realm-client"))));
    }
}
