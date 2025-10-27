package sh.harold.fulcrum.minigame.team;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TeamRegistryTest {

    private final TeamRegistry registry = new TeamRegistry();

    @Test
    void teamOfReturnsEmptyWhenPlayerIsUnassigned() {
        assertTrue(registry.teamOf(UUID.randomUUID()).isEmpty());
    }

    @Test
    void teamByIdReturnsEmptyForNullId() {
        assertTrue(registry.teamById(null).isEmpty());
    }
}
