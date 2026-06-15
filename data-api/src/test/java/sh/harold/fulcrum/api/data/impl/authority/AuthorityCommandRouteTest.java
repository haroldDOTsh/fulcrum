package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityCommandRouteTest {
    @Test
    void rankCommandsRouteByRankAggregatePartition() {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(
            "GRANT_RANK",
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
    void commandObjectsRouteThroughTheirDeclarationId() {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        DataAuthority.PlayerRankCommand command = new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                UUID.randomUUID(),
                "GRANT_RANK",
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + playerId,
                1_800_000_000_000L,
                "",
                1L
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );

        assertThat(AuthorityCommandRoute.fromCommand(command))
            .isEqualTo(AuthorityCommandRoute.fromDeclarationId("GRANT_RANK", command.scope()));
    }

    @Test
    void commandRouteSourceKeepsDeclarationIdLookupForCompatibilityEntrypoints() throws Exception {
        String source = Files.readString(sourcePath());

        assertThat(methodSlice(
            source,
            "static AuthorityCommandRoute fromCommand",
            "static AuthorityCommandRoute fromDeclarationId"
        ))
            .contains("AuthorityCommandManifest.declaration(command.declarationId())")
            .contains("fromDeclarationId(contract.declarationId(), command.scope())")
            .doesNotContain("from(command.declarationId(), command.scope())");
        assertThat(source).doesNotContain("static AuthorityCommandRoute from(String type");
    }

    @Test
    void rankCommandsPreserveLegacyPlayerScopePartition() {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(
            "REVOKE_RANK",
            "player:00000000-0000-0000-0000-000000000001"
        );

        assertThat(route.partitionKey()).isEqualTo("rank:player:00000000-0000-0000-0000-000000000001");
    }

    @Test
    void profileAndSessionCommandsUseDocumentedPublicRoutes() {
        AuthorityCommandRoute login = AuthorityCommandRoute.fromDeclarationId(
            "RECORD_PLAYER_LOGIN",
            "player:00000000-0000-0000-0000-000000000002"
        );
        AuthorityCommandRoute session = AuthorityCommandRoute.fromDeclarationId(
            "RENEW_SESSION",
            "subject:00000000-0000-0000-0000-000000000002"
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
        assertThat(session.partitionKey()).isEqualTo("subject:00000000-0000-0000-0000-000000000002");
    }

    @Test
    void matchCommandsRouteByMatchScope() {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(
            "RECORD_MATCH_START",
            "match:00000000-0000-0000-0000-000000000003"
        );

        assertThat(route.domain()).isEqualTo("match");
        assertThat(route.commandTopic()).isEqualTo("cmd.match");
        assertThat(route.partitionKey()).isEqualTo("match:00000000-0000-0000-0000-000000000003");
    }

    private static Path sourcePath() {
        Path fromModule = Path.of(
            "src",
            "main",
            "java",
            "sh",
            "harold",
            "fulcrum",
            "api",
            "data",
            "impl",
            "authority",
            "AuthorityCommandRoute.java"
        );
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return Path.of(
            "data-api",
            "src",
            "main",
            "java",
            "sh",
            "harold",
            "fulcrum",
            "api",
            "data",
            "impl",
            "authority",
            "AuthorityCommandRoute.java"
        );
    }

    private static String methodSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker);
        assertThat(start).as(startMarker).isNotNegative();
        assertThat(end).as(endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }
}
