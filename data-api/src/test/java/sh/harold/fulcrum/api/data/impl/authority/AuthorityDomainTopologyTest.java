package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityDomainTopologyTest {
    @Test
    void declaresConsumerGroupAndPrincipalForEveryDeclaredDomain() {
        Map<String, AuthorityDomainDeclarations.DomainDeclaration> declarations = AuthorityDomainDeclarations.all();

        Map<String, AuthorityDomainTopology.DomainTopology> topologies = AuthorityDomainTopology.all();

        assertThat(topologies.keySet()).containsExactlyInAnyOrderElementsOf(declarations.keySet());
        for (Map.Entry<String, AuthorityDomainDeclarations.DomainDeclaration> entry : declarations.entrySet()) {
            AuthorityDomainDeclarations.DomainDeclaration declaration = entry.getValue();
            AuthorityDomainTopology.DomainTopology topology = topologies.get(entry.getKey());
            assertThat(topology.authorityService()).isEqualTo(declaration.authorityService());
            assertThat(topology.consumerGroup()).isEqualTo(declaration.consumerGroup());
            assertThat(topology.authorityPrincipal()).isEqualTo(declaration.authorityPrincipal());
            assertThat(topology.partitionCount()).isEqualTo(declaration.partitionCount());
            assertThat(topology.commandTypes()).containsExactlyElementsOf(declaration.commandTypes());
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

        for (AuthorityDomainDeclarations.DomainDeclaration declaration : AuthorityDomainDeclarations.all().values()) {
            AuthorityDomainTopology.DomainTopology topology = AuthorityDomainTopology.domain(declaration.domain());
            for (AuthorityDomainDeclarations.CommandDeclaration command : declaration.commands()) {
                AuthorityCommandRoute route = AuthorityDomainDeclarations.route(command);

                assertThat(topology.commandTypes()).contains(command.type());
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
    void topologyCoversEveryDeclaredCommandTypeExactlyOnce() {
        Set<DataAuthority.CommandType> declaredTypes = AuthorityDomainDeclarations.all().values().stream()
            .flatMap(declaration -> declaration.commandTypes().stream())
            .collect(Collectors.toUnmodifiableSet());
        Set<DataAuthority.CommandType> commandTypes = AuthorityDomainTopology.all().values().stream()
            .flatMap(topology -> topology.commandTypes().stream())
            .collect(Collectors.toUnmodifiableSet());

        assertThat(commandTypes).containsExactlyInAnyOrderElementsOf(declaredTypes);
        assertThat(AuthorityDomainTopology.all().values().stream()
            .mapToLong(topology -> topology.commandTypes().size())
            .sum()).isEqualTo(commandTypes.size());
    }
}
