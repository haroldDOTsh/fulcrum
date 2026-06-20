package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LobbyClusterE2eVerifierTest {
    @Test
    void bareVerifierParsesEndpointAndKeepsUnknownArgumentsIgnored() {
        LobbyClusterE2eVerifier.Config config = LobbyClusterE2eVerifier.Config.parse(new String[]{
                "--endpoint-host=127.0.0.1",
                "--endpoint-port=25566",
                "--timeout=PT30S",
                "--verify-route-authority-state=true"
        });

        assertEquals(Optional.of("127.0.0.1"), config.endpointHost());
        assertEquals(25566, config.endpointPort());
        assertEquals(Duration.ofSeconds(30), config.timeout());
        assertEquals(Map.of("verify-route-authority-state", "true"), config.ignoredArgs());
    }

    @Test
    void bareVerifierRejectsInvalidArgumentShape() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LobbyClusterE2eVerifier.Config.parse(new String[]{"endpoint-host=127.0.0.1"}));

        assertEquals("Verifier arguments must use --name=value syntax: endpoint-host=127.0.0.1", exception.getMessage());
    }
}
