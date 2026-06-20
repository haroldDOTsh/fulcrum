package sh.harold.fulcrum.sdk.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.EventName;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
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
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthorityBackendSdkTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");

    @Test
    void descriptorDigestIsStableAcrossEquivalentDescriptors() {
        CapabilityDescriptor first = descriptor("noop-backend");
        CapabilityDescriptor second = descriptor("noop-backend");

        assertEquals(
                AuthorityBackendDescriptorDigests.descriptorDigest(first),
                AuthorityBackendDescriptorDigests.descriptorDigest(second));
        assertTrue(AuthorityBackendDescriptorDigests.descriptorDigest(first).matches("[0-9a-f]{64}"));
    }

    @Test
    void runtimeGuardRejectsDeniedReceiptsBeforeWorkerConstruction() {
        AuthorityBackendRegistrationReceipt denied = new AuthorityBackendRegistrationReceipt(
                AuthorityBackendRegistrationStatus.DENIED,
                new CapabilityId("noop-backend"),
                AuthorityBackendDescriptorDigests.descriptorDigest(descriptor("noop-backend")),
                "sha256:no-op-bundle",
                "none",
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                "receipt-denied",
                Optional.of(AuthorityBackendRegistrationRejectionReason.MISSING_CREDENTIAL),
                AuthorityBackendDescriptorDigests.sha256Hex("denied"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AuthorityBackendRuntimeGuard.requireAdmitted(denied));

        assertTrue(exception.getMessage().contains("MISSING_CREDENTIAL"));
    }

    @Test
    void runtimeGuardAcceptsAdmittedReceipts() {
        AuthorityBackendRegistrationReceipt admitted = new AuthorityBackendRegistrationReceipt(
                AuthorityBackendRegistrationStatus.ADMITTED,
                new CapabilityId("noop-backend"),
                AuthorityBackendDescriptorDigests.descriptorDigest(descriptor("noop-backend")),
                "sha256:no-op-bundle",
                AuthorityBackendDescriptorDigests.sha256Hex("plan"),
                Optional.of(new PrincipalId("backend-noop")),
                Optional.of(AuthorityBackendDescriptorDigests.sha256Hex("grants")),
                1,
                NOW,
                "receipt-admitted",
                Optional.empty(),
                AuthorityBackendDescriptorDigests.sha256Hex("admitted"));

        assertEquals(admitted, AuthorityBackendRuntimeGuard.requireAdmitted(admitted));
    }

    @Test
    void wireCodecRoundTripsRegistrationRequestAndReceipt() {
        CapabilityDescriptor descriptor = wireDescriptor();
        HostSecurityContext securityContext = wireSecurityContext();
        AuthorityBackendRegistrationRequest request = AuthorityBackendRegistrationRequest.credentialed(
                descriptor,
                securityContext,
                "sha256:wire-bundle",
                NOW);

        AuthorityBackendRegistrationRequest decodedRequest = AuthorityBackendRegistrationWireCodec.decodeRequest(
                AuthorityBackendRegistrationWireCodec.encodeRequest(request));

        assertEquals(request, decodedRequest);
        assertEquals(
                AuthorityBackendDescriptorDigests.descriptorDigest(request.descriptor()),
                AuthorityBackendDescriptorDigests.descriptorDigest(decodedRequest.descriptor()));

        AuthorityBackendRegistrationReceipt receipt = new AuthorityBackendRegistrationReceipt(
                AuthorityBackendRegistrationStatus.ADMITTED,
                descriptor.capabilityId(),
                request.descriptorDigest(),
                request.bundleDigest(),
                AuthorityBackendDescriptorDigests.sha256Hex("wire-plan"),
                Optional.of(securityContext.identity().principalId()),
                Optional.of(AuthorityBackendDescriptorDigests.grantFingerprint(securityContext.credentialScope())),
                7,
                NOW.plusSeconds(1),
                "receipt-wire",
                Optional.empty(),
                AuthorityBackendDescriptorDigests.sha256Hex("wire-signature"));

        assertEquals(
                receipt,
                AuthorityBackendRegistrationWireCodec.decodeReceipt(
                        AuthorityBackendRegistrationWireCodec.encodeReceipt(receipt)));
    }

    private static CapabilityDescriptor descriptor(String capabilityId) {
        return new CapabilityDescriptor(
                new CapabilityId(capabilityId),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(new CapabilityAuthorityDeclaration("noop.authority", "external-authority", 1)),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }

    private static CapabilityDescriptor wireDescriptor() {
        return new CapabilityDescriptor(
                new CapabilityId("wire-backend"),
                new CapabilityVersion("1.2.3"),
                List.of(new ContractName("wire.required")),
                List.of(new ContractDeclaration(
                        new ContractName("wire.contract"),
                        List.of(new CommandDeclaration(
                                new CommandName("wire.command"),
                                "WireCommand",
                                List.of(
                                        new FieldDeclaration("aggregate_id", FieldType.STRING),
                                        new FieldDeclaration("revision", FieldType.LONG, true)),
                                true)),
                        List.of(new EventDeclaration(
                                new EventName("wire.event"),
                                "WireEvent",
                                List.of(new FieldDeclaration("emitted_at", FieldType.INSTANT)))),
                        Optional.of(new SnapshotDeclaration(
                                "WireSnapshot",
                                List.of(new FieldDeclaration("snapshot_version", FieldType.LONG)))),
                        List.of(new ProjectionDeclaration(
                                "wire_projection",
                                "wire_projection_rows",
                                List.of(new FieldDeclaration("owner", FieldType.STRING)))),
                        List.of(
                                new TopicDeclaration("wire.command.topic", TopicFamily.COMMAND),
                                new TopicDeclaration("wire.event.topic", TopicFamily.EVENT)),
                        List.of(new AclRuleDeclaration(
                                "topic:wire.command.topic",
                                List.of("backend-a"),
                                List.of("backend-b"))))),
                List.of(new CapabilityAuthorityDeclaration("wire.authority", "external-authority", 3)),
                List.of(new ContributionDeclaration(
                        CapabilityExtensionPoint.PROXY_COMMANDS,
                        CapabilityScope.NETWORK,
                        10)),
                List.of(CapabilityScope.NETWORK));
    }

    private static HostSecurityContext wireSecurityContext() {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-wire-backend"),
                        "authority-backend",
                        new PoolId("pool-wire"),
                        new MachineRef("machine-wire"),
                        new PrincipalId("principal-wire-backend")),
                "service-account:wire-backend",
                HostCredentialScope.of(
                        new HostResourceGrant(
                                HostResourceFamily.AUTHORITY_DOMAIN,
                                HostAccessMode.PRODUCE,
                                "wire.authority"),
                        new HostResourceGrant(
                                HostResourceFamily.RESOURCE_CLASS,
                                HostAccessMode.READ,
                                "external-authority")));
    }
}
