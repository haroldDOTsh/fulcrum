package sh.harold.fulcrum.registry.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityDomainTopology;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Registry-side admission check for runtime authority delivery manifests.
 */
public final class AuthorityDeliveryManifestValidator {
    private static final int EXPECTED_MANIFEST_VERSION = 1;
    private static final String EXPECTED_AUTHORITY_SERVER_ID = "registry-service";
    private static final String EXPECTED_RUNTIME_DATA_MODE = "remote-authority";
    private static final String EXPECTED_CACHE_MODE = "watermarked-snapshot-cache";

    private AuthorityDeliveryManifestValidator() {
    }

    /**
     * Check whether a runtime manifest matches the registry authority plane.
     *
     * @param manifest manifest supplied by a runtime node.
     * @return null when accepted, otherwise the admission rejection reason.
     */
    public static String rejection(RuntimeAuthorityDeliveryManifest manifest) {
        if (manifest == null) {
            return "Data Authority delivery manifest is required";
        }
        if (manifest.getManifestVersion() != EXPECTED_MANIFEST_VERSION) {
            return "Data Authority delivery manifest version mismatch";
        }
        if (!Objects.equals(manifest.getAuthorityServerId(), EXPECTED_AUTHORITY_SERVER_ID)) {
            return "Data Authority authority server mismatch";
        }
        if (!Objects.equals(manifest.getRuntimeDataMode(), EXPECTED_RUNTIME_DATA_MODE)) {
            return "Data Authority runtime data mode mismatch";
        }
        if (!Objects.equals(manifest.getCacheMode(), EXPECTED_CACHE_MODE)) {
            return "Data Authority cache mode mismatch";
        }
        if (manifest.getStartupAttestationFingerprint() == null
            || manifest.getStartupAttestationFingerprint().isBlank()) {
            return "Data Authority startup attestation fingerprint is required";
        }
        if (manifest.getCommandSchemaVersion() != DataAuthority.COMMAND_SCHEMA_VERSION) {
            return "Data Authority command schema mismatch";
        }
        if (!Objects.equals(manifest.getCommandContractFingerprint(), AuthorityCommandManifest.fingerprint())) {
            return "Data Authority command contract fingerprint mismatch";
        }
        if (!Objects.equals(
            manifest.getCommandRouteManifestFingerprint(),
            AuthorityCommandManifest.routeManifestFingerprint()
        )) {
            return "Data Authority command route manifest fingerprint mismatch";
        }
        if (manifest.getReadSchemaVersion() != DataAuthorityReadContracts.schemaVersion()) {
            return "Data Authority read schema mismatch";
        }
        if (!Objects.equals(manifest.getReadContractFingerprint(), DataAuthorityReadContracts.fingerprint())) {
            return "Data Authority read contract fingerprint mismatch";
        }
        if (!expectedCommandDomains().equals(safeMap(manifest.getCommandDomainsByType()))) {
            return "Data Authority command domain manifest mismatch";
        }
        if (!expectedCommandDeliveryModes().equals(safeMap(manifest.getCommandDeliveryModesByType()))) {
            return "Data Authority command delivery mode manifest mismatch";
        }
        if (!AuthorityCommandManifest.routePartitionKeyVectors()
            .equals(safeMap(manifest.getCommandPartitionKeyVectorsByType()))) {
            return "Data Authority command partition-key vector manifest mismatch";
        }
        if (!AuthorityCommandManifest.commandTopicsByDeclarationId()
            .equals(safeMap(manifest.getCommandTopicsByType()))) {
            return "Data Authority command topic manifest mismatch";
        }
        if (!AuthorityCommandManifest.responseTopicsByDeclarationId()
            .equals(safeMap(manifest.getCommandResponseTopicsByType()))) {
            return "Data Authority command response topic manifest mismatch";
        }
        if (!AuthorityCommandManifest.eventTopicsByDeclarationId()
            .equals(safeMap(manifest.getCommandEventTopicsByType()))) {
            return "Data Authority command event topic manifest mismatch";
        }
        if (!AuthorityCommandManifest.stateTopicsByDeclarationId()
            .equals(safeMap(manifest.getCommandStateTopicsByType()))) {
            return "Data Authority command state topic manifest mismatch";
        }
        if (!expectedCommandLogStores().equals(safeMap(manifest.getCommandLogStoresByType()))) {
            return "Data Authority command log store manifest mismatch";
        }
        if (!expectedCommandHotProjectionStores().equals(safeMap(manifest.getCommandHotProjectionStoresByType()))) {
            return "Data Authority command hot projection store manifest mismatch";
        }
        if (!expectedCommandHistoryStores().equals(safeMap(manifest.getCommandHistoryStoresByType()))) {
            return "Data Authority command history store manifest mismatch";
        }
        if (!expectedCommandCacheStores().equals(safeMap(manifest.getCommandCacheStoresByType()))) {
            return "Data Authority command cache store manifest mismatch";
        }
        if (!expectedReadProjectionFamilies().equals(safeMap(manifest.getReadProjectionFamiliesByType()))) {
            return "Data Authority read projection manifest mismatch";
        }
        if (!expectedReadServingStores().equals(safeMap(manifest.getReadServingStoresByType()))) {
            return "Data Authority read serving store manifest mismatch";
        }
        if (!expectedReadCacheStores().equals(safeMap(manifest.getReadCacheStoresByType()))) {
            return "Data Authority read cache store manifest mismatch";
        }
        if (!Objects.equals(
            manifest.getAuthorityDomainTopologyFingerprint(),
            AuthorityDomainTopology.fingerprint()
        )) {
            return "Data Authority domain topology fingerprint mismatch";
        }
        if (!expectedAuthorityServicesByDomain().equals(safeMap(manifest.getAuthorityServicesByDomain()))) {
            return "Data Authority authority service topology mismatch";
        }
        if (!expectedAuthorityConsumerGroupsByDomain()
            .equals(safeMap(manifest.getAuthorityConsumerGroupsByDomain()))) {
            return "Data Authority consumer group topology mismatch";
        }
        if (!expectedAuthorityPrincipalsByDomain().equals(safeMap(manifest.getAuthorityPrincipalsByDomain()))) {
            return "Data Authority authority principal topology mismatch";
        }
        if (!expectedCommandAuthorityServices().equals(safeMap(manifest.getCommandAuthorityServicesByType()))) {
            return "Data Authority command authority service manifest mismatch";
        }
        if (!expectedCommandConsumerGroups().equals(safeMap(manifest.getCommandConsumerGroupsByType()))) {
            return "Data Authority command consumer group manifest mismatch";
        }
        if (!expectedCommandAuthorityPrincipals()
            .equals(safeMap(manifest.getCommandAuthorityPrincipalsByType()))) {
            return "Data Authority command authority principal manifest mismatch";
        }
        if (!expectedCommandPartitionCounts().equals(safeMap(manifest.getCommandPartitionCountsByType()))) {
            return "Data Authority command partition-count manifest mismatch";
        }

        String expectedFingerprint = RuntimeAuthorityDeliveryManifest.fingerprint(
            manifest.getNodeKind(),
            manifest.getManifestVersion(),
            manifest.getAuthorityServerId(),
            manifest.getRuntimeDataMode(),
            manifest.getCacheMode(),
            manifest.getStartupAttestationFingerprint(),
            manifest.getCommandSchemaVersion(),
            manifest.getCommandContractFingerprint(),
            manifest.getCommandRouteManifestFingerprint(),
            manifest.getAuthorityDomainTopologyFingerprint(),
            manifest.getAuthorityServicesByDomain(),
            manifest.getAuthorityConsumerGroupsByDomain(),
            manifest.getAuthorityPrincipalsByDomain(),
            manifest.getReadSchemaVersion(),
            manifest.getReadContractFingerprint(),
            manifest.getCommandDomainsByType(),
            manifest.getCommandDeliveryModesByType(),
            manifest.getCommandPartitionKeyVectorsByType(),
            manifest.getCommandAuthorityServicesByType(),
            manifest.getCommandConsumerGroupsByType(),
            manifest.getCommandAuthorityPrincipalsByType(),
            manifest.getCommandPartitionCountsByType(),
            manifest.getCommandTopicsByType(),
            manifest.getCommandResponseTopicsByType(),
            manifest.getCommandEventTopicsByType(),
            manifest.getCommandStateTopicsByType(),
            manifest.getCommandLogStoresByType(),
            manifest.getCommandHotProjectionStoresByType(),
            manifest.getCommandHistoryStoresByType(),
            manifest.getCommandCacheStoresByType(),
            manifest.getReadProjectionFamiliesByType(),
            manifest.getReadServingStoresByType(),
            manifest.getReadCacheStoresByType()
        );
        if (!Objects.equals(manifest.getManifestFingerprint(), expectedFingerprint)) {
            return "Data Authority delivery manifest fingerprint mismatch";
        }
        return null;
    }

    /**
     * Require a runtime manifest to match the registry authority plane.
     *
     * @param manifest manifest supplied by a runtime node.
     */
    public static void requireValid(RuntimeAuthorityDeliveryManifest manifest) {
        String rejection = rejection(manifest);
        if (rejection != null) {
            throw new IllegalArgumentException(rejection);
        }
    }

    private static Map<String, String> expectedAuthorityServicesByDomain() {
        return expectedDomainMetadata(AuthorityDomainTopology.DomainTopology::authorityService);
    }

    private static Map<String, String> expectedAuthorityConsumerGroupsByDomain() {
        return expectedDomainMetadata(AuthorityDomainTopology.DomainTopology::consumerGroup);
    }

    private static Map<String, String> expectedAuthorityPrincipalsByDomain() {
        return expectedDomainMetadata(AuthorityDomainTopology.DomainTopology::authorityPrincipal);
    }

    private static Map<String, String> expectedCommandDomains() {
        return AuthorityCommandManifest.domainsByDeclarationId();
    }

    private static Map<String, String> expectedCommandDeliveryModes() {
        return AuthorityCommandManifest.deliveryModesByDeclarationId();
    }

    private static Map<String, String> expectedCommandAuthorityServices() {
        return AuthorityCommandManifest.authorityServicesByDeclarationId();
    }

    private static Map<String, String> expectedCommandConsumerGroups() {
        return AuthorityCommandManifest.consumerGroupsByDeclarationId();
    }

    private static Map<String, String> expectedCommandAuthorityPrincipals() {
        return AuthorityCommandManifest.authorityPrincipalsByDeclarationId();
    }

    private static Map<String, String> expectedCommandPartitionCounts() {
        return AuthorityCommandManifest.partitionCountsByDeclarationId();
    }

    private static Map<String, String> expectedCommandLogStores() {
        return AuthorityCommandManifest.commandLogStoresByDeclarationId();
    }

    private static Map<String, String> expectedCommandHotProjectionStores() {
        return AuthorityCommandManifest.hotProjectionStoresByDeclarationId();
    }

    private static Map<String, String> expectedCommandHistoryStores() {
        return AuthorityCommandManifest.historyStoresByDeclarationId();
    }

    private static Map<String, String> expectedCommandCacheStores() {
        return AuthorityCommandManifest.cacheStoresByDeclarationId();
    }

    private static Map<String, String> expectedReadProjectionFamilies() {
        return expectedReadMetadata(DataAuthorityReadContracts.ReadContract::projectionFamily);
    }

    private static Map<String, String> expectedReadServingStores() {
        return expectedReadMetadata(DataAuthorityReadContracts.ReadContract::servingStore);
    }

    private static Map<String, String> expectedReadCacheStores() {
        return expectedReadMetadata(DataAuthorityReadContracts.ReadContract::cacheStore);
    }

    private static Map<String, String> expectedDomainMetadata(
        Function<AuthorityDomainTopology.DomainTopology, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        AuthorityDomainTopology.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> expectedReadMetadata(
        Function<DataAuthorityReadContracts.ReadContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        DataAuthorityReadContracts.all().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey().name(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> safeMap(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
