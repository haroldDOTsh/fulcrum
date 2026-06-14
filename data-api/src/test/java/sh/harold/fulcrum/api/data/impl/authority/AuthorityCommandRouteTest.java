package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandRouteTest {
    @Test
    void rankCommandsRouteByRankAggregatePartition() {
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:00000000-0000-0000-0000-000000000001"
        );

        assertThat(route.domain()).isEqualTo("player_rank");
        assertThat(route.commandTopic()).isEqualTo("cmd.player_rank");
        assertThat(route.eventTopic()).isEqualTo("evt.player_rank");
        assertThat(route.stateTopic()).isEqualTo("state.player_rank");
        assertThat(route.partitionKey()).isEqualTo("rank:player:00000000-0000-0000-0000-000000000001");
    }

    @Test
    void rankCommandsPreserveLegacyPlayerScopePartition() {
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.REVOKE_RANK,
            "player:00000000-0000-0000-0000-000000000001"
        );

        assertThat(route.partitionKey()).isEqualTo("rank:player:00000000-0000-0000-0000-000000000001");
    }

    @Test
    void profileAndSessionCommandsSharePlayerProfileRoute() {
        AuthorityCommandRoute login = AuthorityCommandRoute.from(
            DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
            "player:00000000-0000-0000-0000-000000000002"
        );
        AuthorityCommandRoute session = AuthorityCommandRoute.from(
            DataAuthority.CommandType.RENEW_SESSION,
            "player:00000000-0000-0000-0000-000000000002"
        );

        assertThat(login.domain()).isEqualTo("player_profile");
        assertThat(session).isEqualTo(login);
    }

    @Test
    void matchCommandsRouteByMatchScope() {
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.RECORD_MATCH_START,
            "match:00000000-0000-0000-0000-000000000003"
        );

        assertThat(route.domain()).isEqualTo("match");
        assertThat(route.commandTopic()).isEqualTo("cmd.match");
        assertThat(route.partitionKey()).isEqualTo("match:00000000-0000-0000-0000-000000000003");
    }
}
