package sh.harold.fulcrum.host.api;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HostCredentialScopeTest {
    @Test
    void paperCredentialsAreScopedToDeclaredHostResources() {
        HostCredentialScope scope = HostCredentialScope.of(
                new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.session"),
                new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "route.instance-1"),
                new HostResourceGrant(HostResourceFamily.CACHE, HostAccessMode.READ, "session.session-1"),
                new HostResourceGrant(HostResourceFamily.HOT_PROJECTION, HostAccessMode.READ, "presence.subject-1"),
                new HostResourceGrant(HostResourceFamily.ARTIFACT, HostAccessMode.READ, "artifact.map-template-1"));

        assertTrue(scope.permits(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.session"));
        assertTrue(scope.permits(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "route.instance-1"));
        assertTrue(scope.permits(HostResourceFamily.CACHE, HostAccessMode.READ, "session.session-1"));
        assertTrue(scope.permits(HostResourceFamily.HOT_PROJECTION, HostAccessMode.READ, "presence.subject-1"));
        assertTrue(scope.permits(HostResourceFamily.ARTIFACT, HostAccessMode.READ, "artifact.map-template-1"));
        assertFalse(scope.permits(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.rank"));
        assertFalse(scope.permits(HostResourceFamily.CACHE, HostAccessMode.READ, "session.other"));
    }

    @Test
    void hostAccessModesDoNotExposeCanonicalStoreWrites() {
        assertEquals(Set.of(HostAccessMode.PRODUCE, HostAccessMode.CONSUME, HostAccessMode.READ), Set.of(HostAccessMode.values()));
    }

    @Test
    void securityContextCarriesCredentialReferenceWithoutSecrets() {
        HostInstanceIdentity identity = new HostInstanceIdentity(
                new InstanceId("instance-paper-1"),
                HostInstanceKinds.PAPER,
                new PoolId("pool-paper-small"),
                new MachineRef("machine-a"),
                new PrincipalId("principal-host-paper-1"));
        HostSecurityContext context = new HostSecurityContext(
                identity,
                "service-account:paper-agent",
                HostCredentialScope.of(new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.session")));

        assertEquals(identity, context.identity());
        assertEquals("service-account:paper-agent", context.credentialRef());
        assertTrue(context.credentialScope().permits(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.session"));
    }
}
