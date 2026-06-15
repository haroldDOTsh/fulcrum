package sh.harold.fulcrum.registry.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityDomainTopology;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityDeliveryManifestValidatorTest {
    @Test
    void acceptsCurrentRuntimeManifest() {
        assertThat(AuthorityDeliveryManifestValidator.rejection(validManifest())).isNull();
    }

    @Test
    void rejectsAbsentRuntimeManifest() {
        assertThat(AuthorityDeliveryManifestValidator.rejection(null))
            .isEqualTo("Data Authority delivery manifest is required");
    }

    @Test
    void rejectsUnsupportedManifestVersion() {
        RuntimeAuthorityDeliveryManifest manifest = validManifest();
        manifest.setManifestVersion(2);
        manifest.setManifestFingerprint(RuntimeAuthorityDeliveryManifest.fingerprint(
            manifest.getNodeKind(),
            manifest.getManifestVersion(),
            manifest.getAuthorityServerId(),
            manifest.getRuntimeDataMode(),
            manifest.getCacheMode(),
            manifest.getStartupAttestationFingerprint(),
            manifest.getCommandSchemaVersion(),
            manifest.getCommandContractFingerprint(),
            manifest.getCommandRouteManifestFingerprint(),
            manifest.getReadSchemaVersion(),
            manifest.getReadContractFingerprint(),
            manifest.getCommandDomainsByType(),
            manifest.getCommandDeliveryModesByType(),
            manifest.getCommandPartitionKeyVectorsByType(),
            manifest.getReadProjectionFamiliesByType()
        ));

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority delivery manifest version mismatch");
    }

    @Test
    void rejectsWrongAuthorityServer() {
        RuntimeAuthorityDeliveryManifest manifest = manifest(
            "registry-shadow",
            "remote-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint"
        );

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority authority server mismatch");
    }

    @Test
    void rejectsLocalRuntimeAuthorityMode() {
        RuntimeAuthorityDeliveryManifest manifest = manifest(
            "registry-service",
            "local-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint"
        );

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority runtime data mode mismatch");
    }

    @Test
    void rejectsMissingStartupAttestationFingerprint() {
        RuntimeAuthorityDeliveryManifest manifest = manifest(
            "registry-service",
            "remote-authority",
            "watermarked-snapshot-cache",
            ""
        );

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority startup attestation fingerprint is required");
    }

    @Test
    void rejectsManifestMissingPartitionKeyVectors() {
        RuntimeAuthorityDeliveryManifest manifest = validManifest();
        manifest.setCommandPartitionKeyVectorsByType(Map.of());

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority command partition-key vector manifest mismatch");
    }

    @Test
    void rejectsManifestMissingDomainTopologyMetadata() {
        RuntimeAuthorityDeliveryManifest manifest = validManifest();
        manifest.setAuthorityDomainTopologyFingerprint("");

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority domain topology fingerprint mismatch");
    }

    @Test
    void rejectsManifestMissingCommandTopologyMetadata() {
        RuntimeAuthorityDeliveryManifest manifest = validManifest();
        manifest.setCommandConsumerGroupsByType(Map.of());

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority command consumer group manifest mismatch");
    }

    @Test
    void rejectsManifestMissingStorePlacementMetadata() {
        RuntimeAuthorityDeliveryManifest manifest = validManifest();
        manifest.setCommandLogStoresByType(Map.of());

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority command log store manifest mismatch");
    }

    @Test
    void rejectsManifestMissingRouteTopicMetadata() {
        RuntimeAuthorityDeliveryManifest manifest = validManifest();
        manifest.setCommandTopicsByType(Map.of());

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority command topic manifest mismatch");
    }

    @Test
    void rejectsTamperedManifestFingerprint() {
        RuntimeAuthorityDeliveryManifest manifest = validManifest();
        manifest.setManifestFingerprint("tampered");

        assertThat(AuthorityDeliveryManifestValidator.rejection(manifest))
            .isEqualTo("Data Authority delivery manifest fingerprint mismatch");
    }

    private static RuntimeAuthorityDeliveryManifest validManifest() {
        return manifest(
            "registry-service",
            "remote-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint"
        );
    }

    private static RuntimeAuthorityDeliveryManifest manifest(
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint
    ) {
        return RuntimeAuthorityDeliveryManifest.create(
            "Paper",
            1,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            DataAuthority.COMMAND_SCHEMA_VERSION,
            AuthorityCommandManifest.fingerprint(),
            AuthorityCommandManifest.routeManifestFingerprint(),
            AuthorityDomainTopology.fingerprint(),
            authorityServicesByDomain(),
            authorityConsumerGroupsByDomain(),
            authorityPrincipalsByDomain(),
            DataAuthorityReadContracts.schemaVersion(),
            DataAuthorityReadContracts.fingerprint(),
            commandDomainsByType(),
            commandDeliveryModesByType(),
            AuthorityCommandManifest.routePartitionKeyVectors(),
            commandAuthorityServicesByType(),
            commandConsumerGroupsByType(),
            commandAuthorityPrincipalsByType(),
            commandPartitionCountsByType(),
            AuthorityCommandManifest.commandTopicsByDeclarationId(),
            AuthorityCommandManifest.responseTopicsByDeclarationId(),
            AuthorityCommandManifest.eventTopicsByDeclarationId(),
            AuthorityCommandManifest.stateTopicsByDeclarationId(),
            commandLogStoresByType(),
            commandHotProjectionStoresByType(),
            commandHistoryStoresByType(),
            commandCacheStoresByType(),
            readProjectionFamiliesByType(),
            readServingStoresByType(),
            readCacheStoresByType()
        );
    }

    private static Map<String, String> commandDomainsByType() {
        return commandMetadataByType(AuthorityCommandManifest.CommandContract::domain);
    }

    private static Map<String, String> commandDeliveryModesByType() {
        return commandMetadataByType(contract -> contract.deliveryMode().name());
    }

    private static Map<String, String> authorityServicesByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::authorityService);
    }

    private static Map<String, String> authorityConsumerGroupsByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::consumerGroup);
    }

    private static Map<String, String> authorityPrincipalsByDomain() {
        return domainTopologyMetadata(AuthorityDomainTopology.DomainTopology::authorityPrincipal);
    }

    private static Map<String, String> commandAuthorityServicesByType() {
        return commandTopologyMetadataByType(AuthorityDomainTopology.DomainTopology::authorityService);
    }

    private static Map<String, String> commandConsumerGroupsByType() {
        return commandTopologyMetadataByType(AuthorityDomainTopology.DomainTopology::consumerGroup);
    }

    private static Map<String, String> commandAuthorityPrincipalsByType() {
        return commandTopologyMetadataByType(AuthorityDomainTopology.DomainTopology::authorityPrincipal);
    }

    private static Map<String, String> commandPartitionCountsByType() {
        return commandTopologyMetadataByType(topology -> Integer.toString(topology.partitionCount()));
    }

    private static Map<String, String> commandLogStoresByType() {
        return commandMetadataByType(AuthorityCommandManifest.CommandContract::commandLogStore);
    }

    private static Map<String, String> commandHotProjectionStoresByType() {
        return commandMetadataByType(AuthorityCommandManifest.CommandContract::hotProjectionStore);
    }

    private static Map<String, String> commandHistoryStoresByType() {
        return commandMetadataByType(AuthorityCommandManifest.CommandContract::historyStore);
    }

    private static Map<String, String> commandCacheStoresByType() {
        return commandMetadataByType(AuthorityCommandManifest.CommandContract::cacheStore);
    }

    private static Map<String, String> readProjectionFamiliesByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::projectionFamily);
    }

    private static Map<String, String> readServingStoresByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::servingStore);
    }

    private static Map<String, String> readCacheStoresByType() {
        return readMetadataByType(DataAuthorityReadContracts.ReadContract::cacheStore);
    }

    private static Map<String, String> commandMetadataByType(
        Function<AuthorityCommandManifest.CommandContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        AuthorityCommandManifest.allByDeclarationId().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> commandTopologyMetadataByType(
        Function<AuthorityDomainTopology.DomainTopology, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        AuthorityCommandManifest.allByDeclarationId().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(
                entry.getKey(),
                extractor.apply(AuthorityDomainTopology.domain(entry.getValue().domain()))
            ));
        return Map.copyOf(values);
    }

    private static Map<String, String> domainTopologyMetadata(
        Function<AuthorityDomainTopology.DomainTopology, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        AuthorityDomainTopology.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> readMetadataByType(
        Function<DataAuthorityReadContracts.ReadContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        DataAuthorityReadContracts.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey().name(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }
}
