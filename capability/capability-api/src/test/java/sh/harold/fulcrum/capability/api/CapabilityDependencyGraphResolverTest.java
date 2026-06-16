package sh.harold.fulcrum.capability.api;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.data.contract.ContractDeclaration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapabilityDependencyGraphResolverTest {
    @Test
    void resolvesContractProvidersAndCapabilityDependencies() {
        CapabilityDescriptor profile = descriptor("player-profile", List.of(), List.of(contract("profile.v1")));
        CapabilityDescriptor rank = descriptor("rank", List.of(contractName("profile.v1")), List.of(contract("rank.v1")));
        CapabilityDescriptor chat = descriptor(
                "chat-decoration",
                List.of(contractName("profile.v1"), contractName("rank.v1")),
                List.of(contract("chat-decoration.v1")));

        CapabilityValidationResult result = CapabilityDependencyGraphResolver.validate(List.of(profile, rank, chat));
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(List.of(profile, rank, chat));

        assertTrue(result.valid());
        assertEquals(
                new CapabilityId("player-profile"),
                graph.providerOf(contractName("profile.v1")).orElseThrow());
        assertEquals(
                List.of(new CapabilityId("player-profile")),
                graph.dependenciesFor(new CapabilityId("rank")));
        assertEquals(
                List.of(new CapabilityId("player-profile"), new CapabilityId("rank")),
                graph.dependenciesFor(new CapabilityId("chat-decoration")));
    }

    @Test
    void missingRequiredContractIsRejected() {
        CapabilityDescriptor rank = descriptor("rank", List.of(contractName("profile.v1")), List.of(contract("rank.v1")));

        CapabilityValidationResult result = CapabilityDependencyGraphResolver.validate(List.of(rank));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "graph.contract.missing"));
        assertThrows(IllegalArgumentException.class, () -> CapabilityDependencyGraphResolver.resolve(List.of(rank)));
    }

    @Test
    void duplicateContractProvidersAreRejected() {
        CapabilityDescriptor standardRank = descriptor("rank", List.of(), List.of(contract("rank.v1")));
        CapabilityDescriptor alternateRank = descriptor("alternate-rank", List.of(), List.of(contract("rank.v1")));

        CapabilityValidationResult result = CapabilityDependencyGraphResolver.validate(List.of(standardRank, alternateRank));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "graph.contract.provider.duplicate"));
    }

    @Test
    void duplicateCapabilityIdsAreRejected() {
        CapabilityDescriptor first = descriptor("rank", List.of(), List.of(contract("rank.v1")));
        CapabilityDescriptor second = descriptor("rank", List.of(), List.of(contract("rank-alt.v1")));

        CapabilityValidationResult result = CapabilityDependencyGraphResolver.validate(List.of(first, second));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "graph.capability.duplicate"));
    }

    @Test
    void dependencyCyclesAreRejected() {
        CapabilityDescriptor party = descriptor("party", List.of(contractName("friends.v1")), List.of(contract("party.v1")));
        CapabilityDescriptor friends = descriptor("friends", List.of(contractName("party.v1")), List.of(contract("friends.v1")));

        CapabilityValidationResult result = CapabilityDependencyGraphResolver.validate(List.of(party, friends));

        assertFalse(result.valid());
        assertTrue(hasCode(result, "graph.capability.cycle"));
    }

    private static CapabilityDescriptor descriptor(
            String capabilityId,
            List<ContractName> requiredContracts,
            List<ContractDeclaration> declaredContracts) {
        return new CapabilityDescriptor(
                new CapabilityId(capabilityId),
                new CapabilityVersion("1.0.0"),
                requiredContracts,
                declaredContracts,
                List.of(),
                List.of(),
                List.of(CapabilityScope.NETWORK));
    }

    private static ContractName contractName(String name) {
        return new ContractName(name);
    }

    private static ContractDeclaration contract(String name) {
        return new ContractDeclaration(new ContractName(name), List.of(), List.of(), List.of());
    }

    private static boolean hasCode(CapabilityValidationResult result, String code) {
        return result.errors().stream().anyMatch(error -> error.code().equals(code));
    }
}
