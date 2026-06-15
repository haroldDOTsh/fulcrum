package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandFingerprintsTest {
    @Test
    void commandFingerprintIsStableForEquivalentPayloads() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.CommandManifest manifest = DataAuthority.CommandManifest.create(
            commandId,
            "GRANT_RANK",
            "rank-service",
            "rank:player:" + playerId,
            "rank:" + commandId,
            1_800_000_000_000L,
            "8",
            4L
        );
        DataAuthority.PlayerRankCommand first = new DataAuthority.PlayerRankCommand(
            manifest,
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
        DataAuthority.PlayerRankCommand second = new DataAuthority.PlayerRankCommand(
            manifest,
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );

        AuthorityCommandFingerprints.Fingerprint firstFingerprint = AuthorityCommandFingerprints.fingerprint(first);
        AuthorityCommandFingerprints.Fingerprint secondFingerprint = AuthorityCommandFingerprints.fingerprint(second);

        assertThat(firstFingerprint.payloadHash()).isEqualTo(secondFingerprint.payloadHash());
        assertThat(firstFingerprint.commandFingerprint()).isEqualTo(secondFingerprint.commandFingerprint());
        assertThat(firstFingerprint.commandFingerprint()).isNotBlank();
    }

    @Test
    void commandFingerprintIgnoresAuthorityIssuedFencingToken() {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand first = rankCommand(commandId, playerId, "1");
        DataAuthority.PlayerRankCommand retryAfterOwnerChange = rankCommand(commandId, playerId, "2");

        AuthorityCommandFingerprints.Fingerprint firstFingerprint = AuthorityCommandFingerprints.fingerprint(first);
        AuthorityCommandFingerprints.Fingerprint retryFingerprint =
            AuthorityCommandFingerprints.fingerprint(retryAfterOwnerChange);

        assertThat(firstFingerprint.payloadHash()).isEqualTo(retryFingerprint.payloadHash());
        assertThat(firstFingerprint.commandFingerprint()).isEqualTo(retryFingerprint.commandFingerprint());
    }

    @Test
    void commandFingerprintIncludesCommandIdentity() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerRankCommand first = rankCommand(UUID.randomUUID(), playerId, "1");
        DataAuthority.PlayerRankCommand retryWithNewCommandId = rankCommand(UUID.randomUUID(), playerId, "1");

        AuthorityCommandFingerprints.Fingerprint firstFingerprint = AuthorityCommandFingerprints.fingerprint(first);
        AuthorityCommandFingerprints.Fingerprint retryFingerprint =
            AuthorityCommandFingerprints.fingerprint(retryWithNewCommandId);

        assertThat(firstFingerprint.payloadHash()).isEqualTo(retryFingerprint.payloadHash());
        assertThat(firstFingerprint.commandFingerprint()).isNotEqualTo(retryFingerprint.commandFingerprint());
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        UUID commandId,
        UUID playerId,
        String fencingToken
    ) {
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "GRANT_RANK",
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                1_800_000_000_000L,
                fencingToken,
                4L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }
}
