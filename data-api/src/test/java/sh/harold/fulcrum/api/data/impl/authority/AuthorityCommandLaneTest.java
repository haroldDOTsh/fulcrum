package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityCommandLaneTest {
    @Test
    void assignsSameAggregateToSameLane() {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(
            "GRANT_RANK",
            "rank:player:00000000-0000-0000-0000-000000000001"
        );

        AuthorityCommandLane first = AuthorityCommandLane.fromRoute(route, 32);
        AuthorityCommandLane second = AuthorityCommandLane.fromRoute(route, 32);

        assertThat(first.lane()).isEqualTo(second.lane());
        assertThat(first.fencingScope()).isEqualTo("rank:lane:" + first.lane());
        assertThat(first.payload())
            .containsEntry("domain", "rank")
            .containsEntry("partitionKey", "rank:player:00000000-0000-0000-0000-000000000001")
            .containsEntry("laneCount", 32)
            .containsEntry("fencingScope", first.fencingScope());
    }

    @Test
    void keepsLaneStableForKnownRoute() {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(
            "RECORD_PLAYER_LOGIN",
            "player:00000000-0000-0000-0000-000000000002"
        );

        AuthorityCommandLane lane = AuthorityCommandLane.fromRoute(route, 256);

        assertThat(lane.lane()).isEqualTo(70);
        assertThat(lane.laneKeyFingerprint())
            .isEqualTo("627411449d4343469e417b841b07a362868e16d7e8857a9a98f7c5765a8fbb1f");
    }

    @Test
    void separatesDomainsWhenPartitionKeysMatch() {
        AuthorityCommandRoute profileRoute = new AuthorityCommandRoute(
            "player",
            "cmd.player",
            "evt.player",
            "state.player",
            "player:00000000-0000-0000-0000-000000000003"
        );
        AuthorityCommandRoute rankRoute = new AuthorityCommandRoute(
            "rank",
            "cmd.rank",
            "evt.rank",
            "state.rank",
            "player:00000000-0000-0000-0000-000000000003"
        );

        AuthorityCommandLane profileLane = AuthorityCommandLane.fromRoute(profileRoute, 128);
        AuthorityCommandLane rankLane = AuthorityCommandLane.fromRoute(rankRoute, 128);

        assertThat(profileLane.laneKeyFingerprint()).isNotEqualTo(rankLane.laneKeyFingerprint());
        assertThat(profileLane.fencingScope()).startsWith("player:lane:");
        assertThat(rankLane.fencingScope()).startsWith("rank:lane:");
    }

    @Test
    void rankLegacyPlayerScopeUsesNormalizedPartitionForLane() {
        UUID playerId = UUID.randomUUID();
        AuthorityCommandRoute canonical = AuthorityCommandRoute.fromDeclarationId(
            "GRANT_RANK",
            "rank:player:" + playerId
        );
        AuthorityCommandRoute legacy = AuthorityCommandRoute.fromDeclarationId(
            "REVOKE_RANK",
            "player:" + playerId
        );

        assertThat(AuthorityCommandLane.fromRoute(legacy, 64))
            .isEqualTo(AuthorityCommandLane.fromRoute(canonical, 64));
    }

    @Test
    void rejectsInvalidLaneCount() {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(
            "RECORD_MATCH_START",
            "match:00000000-0000-0000-0000-000000000004"
        );

        assertThatThrownBy(() -> AuthorityCommandLane.fromRoute(route, 0))
            .hasMessageContaining("laneCount");
    }
}
