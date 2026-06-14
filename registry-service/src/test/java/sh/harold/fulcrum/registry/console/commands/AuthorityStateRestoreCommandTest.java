package sh.harold.fulcrum.registry.console.commands;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.authority.PostgresAuthorityStateRestoreDrill;
import sh.harold.fulcrum.registry.RegistryService;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorityStateRestoreCommandTest {
    @Test
    void verifyRunsRestoreDrillAndPrintsEvidence() {
        PostgresAuthorityStateRestoreDrill restoreDrill = mock(PostgresAuthorityStateRestoreDrill.class);
        RegistryService registryService = mock(RegistryService.class);
        when(registryService.getAuthorityStateRestoreDrill()).thenReturn(restoreDrill);
        when(restoreDrill.verifyLatestSnapshot("rank:player:abc", "operator check"))
            .thenReturn(result(PostgresAuthorityStateRestoreDrill.Status.VERIFIED, false));

        AuthorityStateRestoreCommand command = new AuthorityStateRestoreCommand(registryService);

        CapturedResult captured = capture(() -> command.execute(new String[] {
            "authorityrestore", "verify", "rank:player:abc", "operator", "check"
        }));

        assertThat(captured.returned()).isTrue();
        assertThat(captured.output())
            .contains("Authority state restore drill result")
            .contains("Scope: rank:player:abc")
            .contains("Status: VERIFIED")
            .contains("Clean: true")
            .contains("Restore Source: CHANGELOG_AND_SNAPSHOT")
            .contains("Verification Fingerprint: ");
        verify(restoreDrill).verifyLatestSnapshot("rank:player:abc", "operator check");
        verify(restoreDrill, never()).restoreLatestSnapshot("rank:player:abc", "operator check");
    }

    @Test
    void restoreReturnsFalseWhenSnapshotStillDiverges() {
        PostgresAuthorityStateRestoreDrill restoreDrill = mock(PostgresAuthorityStateRestoreDrill.class);
        RegistryService registryService = mock(RegistryService.class);
        when(registryService.getAuthorityStateRestoreDrill()).thenReturn(restoreDrill);
        when(restoreDrill.restoreLatestSnapshot("rank:player:abc", "operator-restore"))
            .thenReturn(result(PostgresAuthorityStateRestoreDrill.Status.MISMATCH_FOUND, false));

        AuthorityStateRestoreCommand command = new AuthorityStateRestoreCommand(registryService);

        CapturedResult captured = capture(() -> command.execute(new String[] {
            "authorityrestore", "restore", "rank:player:abc"
        }));

        assertThat(captured.returned()).isFalse();
        assertThat(captured.output())
            .contains("Status: MISMATCH_FOUND")
            .contains("Clean: false")
            .contains("Restored: false");
        verify(restoreDrill).restoreLatestSnapshot("rank:player:abc", "operator-restore");
    }

    @Test
    void disabledAuthorityReportsUnavailable() {
        RegistryService registryService = mock(RegistryService.class);
        when(registryService.getAuthorityStateRestoreDrill()).thenReturn(null);
        AuthorityStateRestoreCommand command = new AuthorityStateRestoreCommand(registryService);

        CapturedResult captured = capture(() -> command.execute(new String[] {
            "authorityrestore", "verify", "rank:player:abc"
        }));

        assertThat(captured.returned()).isFalse();
        assertThat(captured.output()).contains("Central authority state restore drill is not enabled");
    }

    private static PostgresAuthorityStateRestoreDrill.RestoreRunResult result(
        PostgresAuthorityStateRestoreDrill.Status status,
        boolean restored
    ) {
        return new PostgresAuthorityStateRestoreDrill.RestoreRunResult(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "rank:player:abc",
            status,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            7L,
            7L,
            restored,
            "Snapshot restore drill finished",
            new PostgresAuthorityStateRestoreDrill.RestoreEvidence(
                2,
                "schema-fingerprint",
                "CHANGELOG_AND_SNAPSHOT",
                "source-state-fingerprint",
                "snapshot-state-fingerprint",
                "event-chain-hash"
            )
        );
    }

    private static CapturedResult capture(CommandInvocation invocation) {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            return new CapturedResult(invocation.execute(), output.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    @FunctionalInterface
    private interface CommandInvocation {
        boolean execute();
    }

    private record CapturedResult(boolean returned, String output) {
    }
}
