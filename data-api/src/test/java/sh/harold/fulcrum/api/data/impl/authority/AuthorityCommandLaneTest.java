package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityCommandLaneTest {
    @Test
    void assignsSameAggregateToSameLane() {
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:00000000-0000-0000-0000-000000000001"
        );

        AuthorityCommandLane first = AuthorityCommandLane.fromRoute(route, 32);
        AuthorityCommandLane second = AuthorityCommandLane.fromRoute(route, 32);

        assertThat(first.lane()).isEqualTo(second.lane());
        assertThat(first.fencingScope()).isEqualTo("player_rank:lane:" + first.lane());
        assertThat(first.payload())
            .containsEntry("domain", "player_rank")
            .containsEntry("partitionKey", "rank:player:00000000-0000-0000-0000-000000000001")
            .containsEntry("laneCount", 32)
            .containsEntry("fencingScope", first.fencingScope());
    }

    @Test
    void keepsLaneStableForKnownRoute() {
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
            "player:00000000-0000-0000-0000-000000000002"
        );

        AuthorityCommandLane lane = AuthorityCommandLane.fromRoute(route, 256);

        assertThat(lane.lane()).isEqualTo(171);
        assertThat(lane.laneKeyFingerprint())
            .isEqualTo("e2b450a29955faab499a8daf24374c3f61248972aae48f824d4559a27e25100a");
    }

    @Test
    void separatesDomainsWhenPartitionKeysMatch() {
        AuthorityCommandRoute profileRoute = new AuthorityCommandRoute(
            "player_profile",
            "cmd.player_profile",
            "evt.player_profile",
            "state.player_profile",
            "player:00000000-0000-0000-0000-000000000003"
        );
        AuthorityCommandRoute rankRoute = new AuthorityCommandRoute(
            "player_rank",
            "cmd.player_rank",
            "evt.player_rank",
            "state.player_rank",
            "player:00000000-0000-0000-0000-000000000003"
        );

        AuthorityCommandLane profileLane = AuthorityCommandLane.fromRoute(profileRoute, 128);
        AuthorityCommandLane rankLane = AuthorityCommandLane.fromRoute(rankRoute, 128);

        assertThat(profileLane.laneKeyFingerprint()).isNotEqualTo(rankLane.laneKeyFingerprint());
        assertThat(profileLane.fencingScope()).startsWith("player_profile:lane:");
        assertThat(rankLane.fencingScope()).startsWith("player_rank:lane:");
    }

    @Test
    void rankLegacyPlayerScopeUsesNormalizedPartitionForLane() {
        UUID playerId = UUID.randomUUID();
        AuthorityCommandRoute canonical = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + playerId
        );
        AuthorityCommandRoute legacy = AuthorityCommandRoute.from(
            DataAuthority.CommandType.REVOKE_RANK,
            "player:" + playerId
        );

        assertThat(AuthorityCommandLane.fromRoute(legacy, 64))
            .isEqualTo(AuthorityCommandLane.fromRoute(canonical, 64));
    }

    @Test
    void rejectsInvalidLaneCount() {
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.RECORD_MATCH_START,
            "match:00000000-0000-0000-0000-000000000004"
        );

        assertThatThrownBy(() -> AuthorityCommandLane.fromRoute(route, 0))
            .hasMessageContaining("laneCount");
    }
}
