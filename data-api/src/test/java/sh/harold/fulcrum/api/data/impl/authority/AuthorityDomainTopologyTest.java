package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityDomainTopologyTest {
    @Test
    void declaresConsumerGroupAndPrincipalForEveryCommandDomain() {
        Set<String> expectedDomains = DataAuthorityCommandContracts.all().values().stream()
            .map(DataAuthorityCommandContracts.CommandContract::domain)
            .collect(Collectors.toUnmodifiableSet());

        Map<String, AuthorityDomainTopology.DomainTopology> topologies = AuthorityDomainTopology.all();

        assertThat(topologies.keySet()).containsExactlyInAnyOrderElementsOf(expectedDomains);
        for (AuthorityDomainTopology.DomainTopology topology : topologies.values()) {
            assertThat(topology.consumerGroup()).isEqualTo("authority-" + topology.domain());
            assertThat(topology.authorityPrincipal()).isEqualTo("authority-" + topology.domain());
            assertThat(topology.partitionCount()).isEqualTo(AuthorityLogTopology.DEFAULT_PARTITION_COUNT);
            assertThat(topology.commandTypes()).isNotEmpty();
            assertThat(topology.commandTypes())
                .allSatisfy(type -> assertThat(DataAuthorityCommandContracts.contract(type).domain())
                    .isEqualTo(topology.domain()));
        }
        assertThat(topologies.get("rank").commandTypes())
            .containsExactlyInAnyOrder(DataAuthority.CommandType.GRANT_RANK, DataAuthority.CommandType.REVOKE_RANK);
    }

    @Test
    void domainTopologyBindsKafkaTopicFamiliesToConsumerGroups() {
        Map<String, AuthorityLogTopicPolicy> policiesByTopic = AuthorityLogTopology.policiesByTopic();

        for (DataAuthorityCommandContracts.CommandContract contract
            : DataAuthorityCommandContracts.all().values()) {
            String aggregateScopeTemplate = contract.aggregateScopePrefix() + "{aggregateId}";
            AuthorityCommandRoute route = AuthorityCommandRoute.from(contract.type(), aggregateScopeTemplate);
            AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(contract.domain());

            assertThat(topology.commandTypes()).contains(contract.type());
            assertThat(topology.commandTopics()).contains(route.commandTopic());
            assertThat(topology.responseTopics()).contains(route.responseTopic());
            assertThat(topology.eventTopics()).contains(route.eventTopic());
            assertThat(topology.stateTopics()).contains(route.stateTopic());
            assertThat(topology.allTopics()).contains(
                route.commandTopic(),
                route.responseTopic(),
                route.eventTopic(),
                route.stateTopic()
            );
            assertThat(policiesByTopic.get(route.commandTopic()).partitionCount())
                .isEqualTo(topology.partitionCount());
            assertThat(policiesByTopic.get(route.stateTopic()).compacted()).isTrue();
        }
    }

    @Test
    void domainTopologyBindsStorePlacementsToAuthorityDeployables() {
        for (AuthorityDomainTopology.DomainTopology topology : AuthorityDomainTopology.all().values()) {
            assertThat(topology.commandLogStores()).containsExactly("kafka");
            assertThat(topology.hotProjectionStores()).containsExactly("cassandra");
            assertThat(topology.historyStores()).containsExactly("postgresql");
            assertThat(topology.cacheStores()).containsExactly("valkey");
            assertThat(topology.allStores()).containsExactly("cassandra", "kafka", "postgresql", "valkey");
        }
    }

    @Test
    void topologyCoversEveryCommandTypeExactlyOnce() {
        Set<DataAuthority.CommandType> commandTypes = AuthorityDomainTopology.all().values().stream()
            .flatMap(topology -> topology.commandTypes().stream())
            .collect(Collectors.toUnmodifiableSet());

        assertThat(commandTypes).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(DataAuthority.CommandType.class));
        assertThat(AuthorityDomainTopology.all().values().stream()
            .mapToLong(topology -> topology.commandTypes().size())
            .sum()).isEqualTo(commandTypes.size());
    }
}
