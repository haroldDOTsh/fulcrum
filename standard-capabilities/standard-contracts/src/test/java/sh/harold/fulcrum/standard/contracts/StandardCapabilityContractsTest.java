package sh.harold.fulcrum.standard.contracts;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.TopicFamily;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StandardCapabilityContractsTest {
    @Test
    void playerProfileContractDeclaresCanonicalProjectionAndTopics() {
        ContractDeclaration contract = PlayerProfileContracts.contract();

        assertEquals(PlayerProfileContracts.CONTRACT, contract.name());
        assertEquals(PlayerProfileContracts.EFFECTIVE_PROJECTION, contract.projections().getFirst().relationName());
        assertEquals(
                java.util.List.of(TopicFamily.COMMAND, TopicFamily.EVENT, TopicFamily.STATE, TopicFamily.RESPONSE),
                contract.topics().stream().map(topic -> topic.family()).toList());
        assertEquals(4, contract.aclRules().size());
    }

    @Test
    void rankContractDeclaresOneLiveEffectiveRankProjection() {
        ContractDeclaration contract = RankContracts.contract();

        assertEquals(RankContracts.CONTRACT, contract.name());
        assertEquals(1, contract.projections().size());
        assertEquals(RankContracts.EFFECTIVE_PROJECTION, contract.projections().getFirst().relationName());
        assertEquals(
                java.util.List.of(TopicFamily.COMMAND, TopicFamily.EVENT, TopicFamily.STATE, TopicFamily.RESPONSE),
                contract.topics().stream().map(topic -> topic.family()).toList());
    }

    @Test
    void punishmentContractDeclaresActivePunishmentProjectionForLoginGate() {
        ContractDeclaration contract = PunishmentContracts.contract();

        assertEquals(PunishmentContracts.CONTRACT, contract.name());
        assertEquals(1, contract.projections().size());
        assertEquals(PunishmentContracts.ACTIVE_PROJECTION, contract.projections().getFirst().relationName());
        assertEquals(
                java.util.List.of(TopicFamily.COMMAND, TopicFamily.EVENT, TopicFamily.STATE, TopicFamily.RESPONSE),
                contract.topics().stream().map(topic -> topic.family()).toList());
    }

    @Test
    void realmContractDeclaresSnapshotMetadataProjection() {
        ContractDeclaration contract = RealmContracts.contract();

        assertEquals(RealmContracts.CONTRACT, contract.name());
        assertEquals(1, contract.projections().size());
        assertEquals(RealmContracts.SNAPSHOT_METADATA_PROJECTION, contract.projections().getFirst().relationName());
        assertEquals(
                java.util.List.of(TopicFamily.COMMAND, TopicFamily.EVENT, TopicFamily.STATE, TopicFamily.RESPONSE),
                contract.topics().stream().map(topic -> topic.family()).toList());
    }
}
