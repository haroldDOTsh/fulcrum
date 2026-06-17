package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.rank.RankAuthority;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbyCapabilityMaterializationVerifierTest {
    @Test
    void configMatchesCapabilitySeedDefaultsAndValkeyEndpoint() {
        LobbyCapabilityMaterializationVerifier.Config config =
                LobbyCapabilityMaterializationVerifier.Config.fromEnvironment(RuntimeEnvironment.of(Map.of(
                        "FULCRUM_VALKEY_ENDPOINT", "fulcrum-valkey:6379",
                        "FULCRUM_LOBBY_DENIED_USERNAME", "FulcrumBannedOne")));

        assertEquals(new LobbyCapabilityMaterializationVerifier.HostPort("fulcrum-valkey", 6379), config.valkeyEndpoint());
        assertEquals(Duration.ofSeconds(120), config.timeout());
        assertEquals(4, config.subjects().size());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne"),
                config.subjects().getFirst().subjectId());
        assertEquals("Admin", config.subjects().getFirst().expectedRankKey());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo"),
                config.subjects().get(1).subjectId());
        assertEquals("Admin", config.subjects().get(1).expectedRankKey());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotFour"),
                config.subjects().get(2).subjectId());
        assertEquals("Admin", config.subjects().get(2).expectedRankKey());
        assertEquals(
                LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBannedOne"),
                config.subjects().getLast().subjectId());
        assertEquals("Member", config.subjects().getLast().expectedRankKey());
        assertEquals("Banned from the lobby", config.subjects().getLast().expectedPunishmentReason().orElseThrow());
        assertEquals(9, config.checkCount());
    }

    @Test
    void expectedPayloadsUseAuthorityCacheKeys() {
        LobbyCapabilityMaterializationVerifier.Config config =
                LobbyCapabilityMaterializationVerifier.Config.fromEnvironment(RuntimeEnvironment.of(Map.of(
                        "FULCRUM_VALKEY_ENDPOINT", "fulcrum-valkey:6379",
                        "FULCRUM_LOBBY_DENIED_USERNAME", "FulcrumBannedOne")));

        LobbyCapabilityMaterializationVerifier.ExpectedPayloads payloads =
                LobbyCapabilityMaterializationVerifier.expectedPayloads(config);

        var acceptedSubject = config.subjects().getFirst().subjectId();
        var secondAcceptedSubject = config.subjects().get(1).subjectId();
        var scaleOutAcceptedSubject = config.subjects().get(2).subjectId();
        var deniedSubject = config.subjects().getLast().subjectId();
        assertTrue(payloads.cacheKeys().contains(PlayerProfileAuthority.cacheKey(acceptedSubject)));
        assertTrue(payloads.cacheKeys().contains(RankAuthority.cacheKey(acceptedSubject)));
        assertTrue(payloads.cacheKeys().contains(PlayerProfileAuthority.cacheKey(secondAcceptedSubject)));
        assertTrue(payloads.cacheKeys().contains(RankAuthority.cacheKey(secondAcceptedSubject)));
        assertTrue(payloads.cacheKeys().contains(PlayerProfileAuthority.cacheKey(scaleOutAcceptedSubject)));
        assertTrue(payloads.cacheKeys().contains(RankAuthority.cacheKey(scaleOutAcceptedSubject)));
        assertTrue(payloads.cacheKeys().contains(PlayerProfileAuthority.cacheKey(deniedSubject)));
        assertTrue(payloads.cacheKeys().contains(RankAuthority.cacheKey(deniedSubject)));
        assertTrue(payloads.cacheKeys().contains(PunishmentAuthority.cacheKey(deniedSubject)));
    }
}
