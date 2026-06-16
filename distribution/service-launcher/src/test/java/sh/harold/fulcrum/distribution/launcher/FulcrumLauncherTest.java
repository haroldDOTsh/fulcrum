package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FulcrumLauncherTest {
    @Test
    void planModeListsAllServiceAndHostEntrypoints() {
        LaunchResult result = run("--profile=single-machine");

        assertEquals(FulcrumLauncher.OK, result.code());
        assertEquals("", result.err());
        assertTrue(result.out().contains("profile=single-machine"));
        assertTrue(result.out().contains("authority-service"));
        assertTrue(result.out().contains("controller-service"));
        assertTrue(result.out().contains("worker-agent"));
        assertTrue(result.out().contains("paper-agent"));
        assertTrue(result.out().contains("velocity-agent"));
        assertTrue(result.out().contains("fulcrum --role=paper-agent --profile=single-machine --mode=run"));
    }

    @Test
    void runModeFailsFastUntilExternalBindingsExist() {
        LaunchResult result = run("--profile=large-production", "--role=authority-service", "--mode=run");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, result.code());
        assertTrue(result.out().contains("storageShape=full-log-store-topology"));
        assertTrue(result.err().contains("Cannot start Fulcrum runtime"));
        assertTrue(result.err().contains("Kafka command, event, state, and response log clients"));
        assertTrue(result.err().contains("PostgreSQL authority record adapter"));
    }

    @Test
    void profileDescriptorsLoadFromDistributionClasspath() {
        for (DeploymentProfile profile : DeploymentProfile.values()) {
            ProfileDescriptor descriptor = profile.loadDescriptor(Thread.currentThread().getContextClassLoader());

            assertEquals(profile.id(), descriptor.profileId());
            assertEquals("fulcrum-v2-substrate", descriptor.semanticModel());
            assertEquals("fulcrum-step0-contracts", descriptor.contractSet());
            assertTrue(descriptor.resourcePath().endsWith(profile.id() + ".json"));
        }
    }

    @Test
    void invalidRoleReturnsUsageError() {
        LaunchResult result = run("--role=nope");

        assertEquals(FulcrumLauncher.USAGE_ERROR, result.code());
        assertTrue(result.err().contains("Unknown launch role: nope"));
        assertTrue(result.err().contains("Usage: fulcrum"));
    }

    private static LaunchResult run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FulcrumLauncher().run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new LaunchResult(
                code,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private record LaunchResult(int code, String out, String err) {
    }
}
