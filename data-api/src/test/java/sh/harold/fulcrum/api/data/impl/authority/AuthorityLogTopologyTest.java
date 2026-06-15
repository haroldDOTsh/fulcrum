package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityLogTopologyTest {
    @Test
    void topologyDeclaresDocumentedTopicFamilies() {
        Map<String, AuthorityLogTopicPolicy> policies = AuthorityLogTopology.policiesByTopic();

        assertThat(policies).containsKeys(
            "cmd.player",
            "rsp.player",
            "evt.player",
            "state.player",
            "cmd.rank",
            "rsp.rank",
            "evt.rank",
            "state.rank",
            "cmd.session",
            "rsp.session",
            "evt.session",
            "state.session",
            "cmd.match",
            "rsp.match",
            "evt.match",
            "state.match"
        );
        assertThat(policies.get("state.rank").compacted()).isTrue();
        assertThat(policies.get("state.rank").retentionClass()).isEqualTo("compacted-forever");
        assertThat(policies.get("cmd.rank").compacted()).isFalse();
        assertThat(policies.get("cmd.rank").partitionCount())
            .isEqualTo(AuthorityCommandLane.DEFAULT_LANE_COUNT);
        assertThat(policies.get("cmd.rank").keyRule()).isEqualTo("aggregate-id");
        assertThat(policies.get("cmd.rank").producerPrincipalPatterns()).containsExactly("node:*");
        assertThat(policies.get("cmd.rank").consumerPrincipalPatterns()).containsExactly("authority-rank");
        assertThat(policies.get("rsp.rank").producerPrincipalPatterns()).containsExactly("authority-rank");
        assertThat(policies.get("rsp.rank").consumerPrincipalPatterns()).containsExactly("node:*");
        assertThat(policies.get("evt.rank").consumerPrincipalPatterns())
            .containsExactly("ops:*", "projection-worker:*");
        assertThat(policies.get("state.rank").consumerPrincipalPatterns())
            .containsExactly("ops:*", "projection-worker:*", "recovery-worker:*");
    }

    @Test
    void appendsUseAggregateKeyAndCustodyPartition() {
        UUID playerId = UUID.randomUUID();
        AuthorityCommandRoute route = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + playerId
        );
        AuthorityWriteCustody custody = AuthorityWriteCustody.fromRoute(route);
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();

        AuthorityLogRecord record = log.append(
            route,
            AuthorityLogTopicKind.COMMAND,
            payload("commandType", "GRANT_RANK")
        );

        assertThat(record.topic()).isEqualTo("cmd.rank");
        assertThat(record.key()).isEqualTo("rank:player:" + playerId);
        assertThat(record.partition()).isEqualTo(custody.ownershipPartition());
        assertThat(record.offset()).isZero();
        assertThat(log.records("cmd.rank", record.partition())).containsExactly(record);
    }

    @Test
    void compactedStateTopicKeepsLatestRecordPerAggregateKey() {
        UUID firstPlayerId = UUID.randomUUID();
        UUID secondPlayerId = UUID.randomUUID();
        AuthorityCommandRoute firstRoute = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + firstPlayerId
        );
        AuthorityCommandRoute secondRoute = AuthorityCommandRoute.from(
            DataAuthority.CommandType.GRANT_RANK,
            "rank:player:" + secondPlayerId
        );
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();

        log.append(firstRoute, AuthorityLogTopicKind.STATE, payload("revision", 1L));
        AuthorityLogRecord latest = log.append(firstRoute, AuthorityLogTopicKind.STATE, payload("revision", 2L));
        AuthorityLogRecord other = log.append(secondRoute, AuthorityLogTopicKind.STATE, payload("revision", 1L));

        Map<String, AuthorityLogRecord> compacted = log.compacted("state.rank");
        assertThat(compacted).hasSize(2);
        assertThat(compacted.get(firstRoute.partitionKey())).isEqualTo(latest);
        assertThat(compacted.get(secondRoute.partitionKey())).isEqualTo(other);
        assertThat(compacted.get(firstRoute.partitionKey()).payload()).containsEntry("revision", 2L);
    }

    @Test
    void refusesCompactionForNonCompactedTopics() {
        InMemoryAuthorityLog log = new InMemoryAuthorityLog();

        assertThatThrownBy(() -> log.compacted("cmd.rank"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not compacted");
    }

    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index].toString(), pairs[index + 1]);
        }
        return values;
    }
}
