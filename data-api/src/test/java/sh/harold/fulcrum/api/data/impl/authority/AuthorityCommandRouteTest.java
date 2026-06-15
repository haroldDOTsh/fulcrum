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

        assertThat(route.domain()).isEqualTo("rank");
        assertThat(route.commandTopic()).isEqualTo("cmd.rank");
        assertThat(route.responseTopic()).isEqualTo("rsp.rank");
        assertThat(route.eventTopic()).isEqualTo("evt.rank");
        assertThat(route.stateTopic()).isEqualTo("state.rank");
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
    void profileAndSessionCommandsUseDocumentedPublicRoutes() {
        AuthorityCommandRoute login = AuthorityCommandRoute.from(
            DataAuthority.CommandType.RECORD_PLAYER_LOGIN,
            "player:00000000-0000-0000-0000-000000000002"
        );
        AuthorityCommandRoute session = AuthorityCommandRoute.from(
            DataAuthority.CommandType.RENEW_SESSION,
            "player:00000000-0000-0000-0000-000000000002"
        );

        assertThat(login.domain()).isEqualTo("player");
        assertThat(login.commandTopic()).isEqualTo("cmd.player");
        assertThat(login.responseTopic()).isEqualTo("rsp.player");
        assertThat(login.eventTopic()).isEqualTo("evt.player");
        assertThat(login.stateTopic()).isEqualTo("state.player");
        assertThat(login.partitionKey()).isEqualTo("player:00000000-0000-0000-0000-000000000002");

        assertThat(session.domain()).isEqualTo("session");
        assertThat(session.commandTopic()).isEqualTo("cmd.session");
        assertThat(session.responseTopic()).isEqualTo("rsp.session");
        assertThat(session.eventTopic()).isEqualTo("evt.session");
        assertThat(session.stateTopic()).isEqualTo("state.session");
        assertThat(session.partitionKey()).isEqualTo(login.partitionKey());
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
