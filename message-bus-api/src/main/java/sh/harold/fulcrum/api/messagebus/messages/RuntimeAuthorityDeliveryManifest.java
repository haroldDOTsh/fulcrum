package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry-visible manifest describing the Data Authority routes a runtime node may use.
 */
public class RuntimeAuthorityDeliveryManifest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String nodeKind;
    private int manifestVersion;
    private String authorityServerId;
    private String runtimeDataMode;
    private String cacheMode;
    private String startupAttestationFingerprint;
    private int commandSchemaVersion;
    private String commandContractFingerprint;
    private String commandRouteManifestFingerprint;
    private String authorityDomainTopologyFingerprint;
    private int readSchemaVersion;
    private String readContractFingerprint;
    private Map<String, String> authorityServicesByDomain = new LinkedHashMap<>();
    private Map<String, String> authorityConsumerGroupsByDomain = new LinkedHashMap<>();
    private Map<String, String> authorityPrincipalsByDomain = new LinkedHashMap<>();
    private Map<String, String> commandDomainsByType = new LinkedHashMap<>();
    private Map<String, String> commandDeliveryModesByType = new LinkedHashMap<>();
    private Map<String, String> commandPartitionKeyVectorsByType = new LinkedHashMap<>();
    private Map<String, String> commandAuthorityServicesByType = new LinkedHashMap<>();
    private Map<String, String> commandConsumerGroupsByType = new LinkedHashMap<>();
    private Map<String, String> commandAuthorityPrincipalsByType = new LinkedHashMap<>();
    private Map<String, String> commandPartitionCountsByType = new LinkedHashMap<>();
    private Map<String, String> commandTopicsByType = new LinkedHashMap<>();
    private Map<String, String> commandResponseTopicsByType = new LinkedHashMap<>();
    private Map<String, String> commandEventTopicsByType = new LinkedHashMap<>();
    private Map<String, String> commandStateTopicsByType = new LinkedHashMap<>();
    private Map<String, String> commandLogStoresByType = new LinkedHashMap<>();
    private Map<String, String> commandHotProjectionStoresByType = new LinkedHashMap<>();
    private Map<String, String> commandHistoryStoresByType = new LinkedHashMap<>();
    private Map<String, String> commandCacheStoresByType = new LinkedHashMap<>();
    private Map<String, String> readProjectionFamiliesByType = new LinkedHashMap<>();
    private Map<String, String> readServingStoresByType = new LinkedHashMap<>();
    private Map<String, String> readCacheStoresByType = new LinkedHashMap<>();
    private String manifestFingerprint;

    public RuntimeAuthorityDeliveryManifest() {
        // Default constructor for serialization
    }

    public RuntimeAuthorityDeliveryManifest(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> readProjectionFamiliesByType,
        String manifestFingerprint
    ) {
        this(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            readProjectionFamiliesByType,
            Map.of(),
            Map.of(),
            manifestFingerprint
        );
    }

    public RuntimeAuthorityDeliveryManifest(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> readProjectionFamiliesByType,
        String manifestFingerprint
    ) {
        this(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            readProjectionFamiliesByType,
            Map.of(),
            Map.of(),
            manifestFingerprint
        );
    }

    public RuntimeAuthorityDeliveryManifest(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType,
        String manifestFingerprint
    ) {
        this(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType,
            manifestFingerprint
        );
    }

    public RuntimeAuthorityDeliveryManifest(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType,
        String manifestFingerprint
    ) {
        this(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            commandTopicsByType,
            commandResponseTopicsByType,
            commandEventTopicsByType,
            commandStateTopicsByType,
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType,
            manifestFingerprint
        );
    }

    public RuntimeAuthorityDeliveryManifest(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandAuthorityServicesByType,
        Map<String, String> commandConsumerGroupsByType,
        Map<String, String> commandAuthorityPrincipalsByType,
        Map<String, String> commandPartitionCountsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType,
        String manifestFingerprint
    ) {
        this(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            commandAuthorityServicesByType,
            commandConsumerGroupsByType,
            commandAuthorityPrincipalsByType,
            commandPartitionCountsByType,
            commandTopicsByType,
            commandResponseTopicsByType,
            commandEventTopicsByType,
            commandStateTopicsByType,
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType,
            manifestFingerprint
        );
    }

    public RuntimeAuthorityDeliveryManifest(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        String authorityDomainTopologyFingerprint,
        Map<String, String> authorityServicesByDomain,
        Map<String, String> authorityConsumerGroupsByDomain,
        Map<String, String> authorityPrincipalsByDomain,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandAuthorityServicesByType,
        Map<String, String> commandConsumerGroupsByType,
        Map<String, String> commandAuthorityPrincipalsByType,
        Map<String, String> commandPartitionCountsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType,
        String manifestFingerprint
    ) {
        this.nodeKind = nodeKind;
        this.manifestVersion = manifestVersion;
        this.authorityServerId = authorityServerId;
        this.runtimeDataMode = runtimeDataMode;
        this.cacheMode = cacheMode;
        this.startupAttestationFingerprint = startupAttestationFingerprint;
        this.commandSchemaVersion = commandSchemaVersion;
        this.commandContractFingerprint = commandContractFingerprint;
        this.commandRouteManifestFingerprint = commandRouteManifestFingerprint;
        this.authorityDomainTopologyFingerprint = authorityDomainTopologyFingerprint;
        this.readSchemaVersion = readSchemaVersion;
        this.readContractFingerprint = readContractFingerprint;
        setAuthorityServicesByDomain(authorityServicesByDomain);
        setAuthorityConsumerGroupsByDomain(authorityConsumerGroupsByDomain);
        setAuthorityPrincipalsByDomain(authorityPrincipalsByDomain);
        setCommandDomainsByType(commandDomainsByType);
        setCommandDeliveryModesByType(commandDeliveryModesByType);
        setCommandPartitionKeyVectorsByType(commandPartitionKeyVectorsByType);
        setCommandAuthorityServicesByType(commandAuthorityServicesByType);
        setCommandConsumerGroupsByType(commandConsumerGroupsByType);
        setCommandAuthorityPrincipalsByType(commandAuthorityPrincipalsByType);
        setCommandPartitionCountsByType(commandPartitionCountsByType);
        setCommandTopicsByType(commandTopicsByType);
        setCommandResponseTopicsByType(commandResponseTopicsByType);
        setCommandEventTopicsByType(commandEventTopicsByType);
        setCommandStateTopicsByType(commandStateTopicsByType);
        setCommandLogStoresByType(commandLogStoresByType);
        setCommandHotProjectionStoresByType(commandHotProjectionStoresByType);
        setCommandHistoryStoresByType(commandHistoryStoresByType);
        setCommandCacheStoresByType(commandCacheStoresByType);
        setReadProjectionFamiliesByType(readProjectionFamiliesByType);
        setReadServingStoresByType(readServingStoresByType);
        setReadCacheStoresByType(readCacheStoresByType);
        this.manifestFingerprint = manifestFingerprint;
    }

    public static RuntimeAuthorityDeliveryManifest create(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> readProjectionFamiliesByType
    ) {
        return create(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            readProjectionFamiliesByType,
            Map.of(),
            Map.of()
        );
    }

    public static RuntimeAuthorityDeliveryManifest create(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType
    ) {
        return create(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType
        );
    }

    public static RuntimeAuthorityDeliveryManifest create(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType
    ) {
        return create(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            commandTopicsByType,
            commandResponseTopicsByType,
            commandEventTopicsByType,
            commandStateTopicsByType,
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType
        );
    }

    public static RuntimeAuthorityDeliveryManifest create(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandAuthorityServicesByType,
        Map<String, String> commandConsumerGroupsByType,
        Map<String, String> commandAuthorityPrincipalsByType,
        Map<String, String> commandPartitionCountsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType
    ) {
        return create(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            commandTopicsByType,
            commandResponseTopicsByType,
            commandEventTopicsByType,
            commandStateTopicsByType,
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType
        );
    }

    public static RuntimeAuthorityDeliveryManifest create(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        String authorityDomainTopologyFingerprint,
        Map<String, String> authorityServicesByDomain,
        Map<String, String> authorityConsumerGroupsByDomain,
        Map<String, String> authorityPrincipalsByDomain,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandAuthorityServicesByType,
        Map<String, String> commandConsumerGroupsByType,
        Map<String, String> commandAuthorityPrincipalsByType,
        Map<String, String> commandPartitionCountsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType
    ) {
        String manifestFingerprint = fingerprint(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            authorityDomainTopologyFingerprint,
            authorityServicesByDomain,
            authorityConsumerGroupsByDomain,
            authorityPrincipalsByDomain,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            commandAuthorityServicesByType,
            commandConsumerGroupsByType,
            commandAuthorityPrincipalsByType,
            commandPartitionCountsByType,
            commandTopicsByType,
            commandResponseTopicsByType,
            commandEventTopicsByType,
            commandStateTopicsByType,
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType
        );
        return new RuntimeAuthorityDeliveryManifest(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            authorityDomainTopologyFingerprint,
            authorityServicesByDomain,
            authorityConsumerGroupsByDomain,
            authorityPrincipalsByDomain,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            commandAuthorityServicesByType,
            commandConsumerGroupsByType,
            commandAuthorityPrincipalsByType,
            commandPartitionCountsByType,
            commandTopicsByType,
            commandResponseTopicsByType,
            commandEventTopicsByType,
            commandStateTopicsByType,
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType,
            manifestFingerprint
        );
    }

    public static String fingerprint(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> readProjectionFamiliesByType
    ) {
        return fingerprint(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            readProjectionFamiliesByType,
            Map.of(),
            Map.of()
        );
    }

    public static String fingerprint(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType
    ) {
        return fingerprint(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType
        );
    }

    public static String fingerprint(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType
    ) {
        return fingerprint(
            nodeKind,
            manifestVersion,
            authorityServerId,
            runtimeDataMode,
            cacheMode,
            startupAttestationFingerprint,
            commandSchemaVersion,
            commandContractFingerprint,
            commandRouteManifestFingerprint,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            readSchemaVersion,
            readContractFingerprint,
            commandDomainsByType,
            commandDeliveryModesByType,
            commandPartitionKeyVectorsByType,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            commandTopicsByType,
            commandResponseTopicsByType,
            commandEventTopicsByType,
            commandStateTopicsByType,
            commandLogStoresByType,
            commandHotProjectionStoresByType,
            commandHistoryStoresByType,
            commandCacheStoresByType,
            readProjectionFamiliesByType,
            readServingStoresByType,
            readCacheStoresByType
        );
    }

    public static String fingerprint(
        String nodeKind,
        int manifestVersion,
        String authorityServerId,
        String runtimeDataMode,
        String cacheMode,
        String startupAttestationFingerprint,
        int commandSchemaVersion,
        String commandContractFingerprint,
        String commandRouteManifestFingerprint,
        String authorityDomainTopologyFingerprint,
        Map<String, String> authorityServicesByDomain,
        Map<String, String> authorityConsumerGroupsByDomain,
        Map<String, String> authorityPrincipalsByDomain,
        int readSchemaVersion,
        String readContractFingerprint,
        Map<String, String> commandDomainsByType,
        Map<String, String> commandDeliveryModesByType,
        Map<String, String> commandPartitionKeyVectorsByType,
        Map<String, String> commandAuthorityServicesByType,
        Map<String, String> commandConsumerGroupsByType,
        Map<String, String> commandAuthorityPrincipalsByType,
        Map<String, String> commandPartitionCountsByType,
        Map<String, String> commandTopicsByType,
        Map<String, String> commandResponseTopicsByType,
        Map<String, String> commandEventTopicsByType,
        Map<String, String> commandStateTopicsByType,
        Map<String, String> commandLogStoresByType,
        Map<String, String> commandHotProjectionStoresByType,
        Map<String, String> commandHistoryStoresByType,
        Map<String, String> commandCacheStoresByType,
        Map<String, String> readProjectionFamiliesByType,
        Map<String, String> readServingStoresByType,
        Map<String, String> readCacheStoresByType
    ) {
        StringBuilder material = new StringBuilder()
            .append("runtime-authority-delivery-manifest\n")
            .append("nodeKind=").append(value(nodeKind)).append('\n')
            .append("manifestVersion=").append(manifestVersion).append('\n')
            .append("authorityServerId=").append(value(authorityServerId)).append('\n')
            .append("runtimeDataMode=").append(value(runtimeDataMode)).append('\n')
            .append("cacheMode=").append(value(cacheMode)).append('\n')
            .append("startupAttestationFingerprint=").append(value(startupAttestationFingerprint)).append('\n')
            .append("commandSchemaVersion=").append(commandSchemaVersion).append('\n')
            .append("commandContractFingerprint=").append(value(commandContractFingerprint)).append('\n')
            .append("commandRouteManifestFingerprint=").append(value(commandRouteManifestFingerprint)).append('\n')
            .append("authorityDomainTopologyFingerprint=")
            .append(value(authorityDomainTopologyFingerprint))
            .append('\n')
            .append("readSchemaVersion=").append(readSchemaVersion).append('\n')
            .append("readContractFingerprint=").append(value(readContractFingerprint)).append('\n');
        appendMap(material, "authorityServicesByDomain", authorityServicesByDomain);
        appendMap(material, "authorityConsumerGroupsByDomain", authorityConsumerGroupsByDomain);
        appendMap(material, "authorityPrincipalsByDomain", authorityPrincipalsByDomain);
        appendMap(material, "commandDomainsByType", commandDomainsByType);
        appendMap(material, "commandDeliveryModesByType", commandDeliveryModesByType);
        appendMap(material, "commandPartitionKeyVectorsByType", commandPartitionKeyVectorsByType);
        appendMap(material, "commandAuthorityServicesByType", commandAuthorityServicesByType);
        appendMap(material, "commandConsumerGroupsByType", commandConsumerGroupsByType);
        appendMap(material, "commandAuthorityPrincipalsByType", commandAuthorityPrincipalsByType);
        appendMap(material, "commandPartitionCountsByType", commandPartitionCountsByType);
        appendMap(material, "commandTopicsByType", commandTopicsByType);
        appendMap(material, "commandResponseTopicsByType", commandResponseTopicsByType);
        appendMap(material, "commandEventTopicsByType", commandEventTopicsByType);
        appendMap(material, "commandStateTopicsByType", commandStateTopicsByType);
        appendMap(material, "commandLogStoresByType", commandLogStoresByType);
        appendMap(material, "commandHotProjectionStoresByType", commandHotProjectionStoresByType);
        appendMap(material, "commandHistoryStoresByType", commandHistoryStoresByType);
        appendMap(material, "commandCacheStoresByType", commandCacheStoresByType);
        appendMap(material, "readProjectionFamiliesByType", readProjectionFamiliesByType);
        appendMap(material, "readServingStoresByType", readServingStoresByType);
        appendMap(material, "readCacheStoresByType", readCacheStoresByType);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint runtime authority delivery manifest", exception);
        }
    }

    public String getNodeKind() {
        return nodeKind;
    }

    public void setNodeKind(String nodeKind) {
        this.nodeKind = nodeKind;
    }

    public int getManifestVersion() {
        return manifestVersion;
    }

    public void setManifestVersion(int manifestVersion) {
        this.manifestVersion = manifestVersion;
    }

    public String getAuthorityServerId() {
        return authorityServerId;
    }

    public void setAuthorityServerId(String authorityServerId) {
        this.authorityServerId = authorityServerId;
    }

    public String getRuntimeDataMode() {
        return runtimeDataMode;
    }

    public void setRuntimeDataMode(String runtimeDataMode) {
        this.runtimeDataMode = runtimeDataMode;
    }

    public String getCacheMode() {
        return cacheMode;
    }

    public void setCacheMode(String cacheMode) {
        this.cacheMode = cacheMode;
    }

    public String getStartupAttestationFingerprint() {
        return startupAttestationFingerprint;
    }

    public void setStartupAttestationFingerprint(String startupAttestationFingerprint) {
        this.startupAttestationFingerprint = startupAttestationFingerprint;
    }

    public int getCommandSchemaVersion() {
        return commandSchemaVersion;
    }

    public void setCommandSchemaVersion(int commandSchemaVersion) {
        this.commandSchemaVersion = commandSchemaVersion;
    }

    public String getCommandContractFingerprint() {
        return commandContractFingerprint;
    }

    public void setCommandContractFingerprint(String commandContractFingerprint) {
        this.commandContractFingerprint = commandContractFingerprint;
    }

    public String getCommandRouteManifestFingerprint() {
        return commandRouteManifestFingerprint;
    }

    public void setCommandRouteManifestFingerprint(String commandRouteManifestFingerprint) {
        this.commandRouteManifestFingerprint = commandRouteManifestFingerprint;
    }

    public String getAuthorityDomainTopologyFingerprint() {
        return authorityDomainTopologyFingerprint;
    }

    public void setAuthorityDomainTopologyFingerprint(String authorityDomainTopologyFingerprint) {
        this.authorityDomainTopologyFingerprint = authorityDomainTopologyFingerprint;
    }

    public int getReadSchemaVersion() {
        return readSchemaVersion;
    }

    public void setReadSchemaVersion(int readSchemaVersion) {
        this.readSchemaVersion = readSchemaVersion;
    }

    public String getReadContractFingerprint() {
        return readContractFingerprint;
    }

    public void setReadContractFingerprint(String readContractFingerprint) {
        this.readContractFingerprint = readContractFingerprint;
    }

    public Map<String, String> getAuthorityServicesByDomain() {
        return authorityServicesByDomain;
    }

    public void setAuthorityServicesByDomain(Map<String, String> authorityServicesByDomain) {
        this.authorityServicesByDomain = copyMap(authorityServicesByDomain);
    }

    public Map<String, String> getAuthorityConsumerGroupsByDomain() {
        return authorityConsumerGroupsByDomain;
    }

    public void setAuthorityConsumerGroupsByDomain(Map<String, String> authorityConsumerGroupsByDomain) {
        this.authorityConsumerGroupsByDomain = copyMap(authorityConsumerGroupsByDomain);
    }

    public Map<String, String> getAuthorityPrincipalsByDomain() {
        return authorityPrincipalsByDomain;
    }

    public void setAuthorityPrincipalsByDomain(Map<String, String> authorityPrincipalsByDomain) {
        this.authorityPrincipalsByDomain = copyMap(authorityPrincipalsByDomain);
    }

    public Map<String, String> getCommandDomainsByType() {
        return commandDomainsByType;
    }

    public void setCommandDomainsByType(Map<String, String> commandDomainsByType) {
        this.commandDomainsByType = copyMap(commandDomainsByType);
    }

    public Map<String, String> getCommandDeliveryModesByType() {
        return commandDeliveryModesByType;
    }

    public void setCommandDeliveryModesByType(Map<String, String> commandDeliveryModesByType) {
        this.commandDeliveryModesByType = copyMap(commandDeliveryModesByType);
    }

    public Map<String, String> getCommandPartitionKeyVectorsByType() {
        return commandPartitionKeyVectorsByType;
    }

    public void setCommandPartitionKeyVectorsByType(Map<String, String> commandPartitionKeyVectorsByType) {
        this.commandPartitionKeyVectorsByType = copyMap(commandPartitionKeyVectorsByType);
    }

    public Map<String, String> getCommandAuthorityServicesByType() {
        return commandAuthorityServicesByType;
    }

    public void setCommandAuthorityServicesByType(Map<String, String> commandAuthorityServicesByType) {
        this.commandAuthorityServicesByType = copyMap(commandAuthorityServicesByType);
    }

    public Map<String, String> getCommandConsumerGroupsByType() {
        return commandConsumerGroupsByType;
    }

    public void setCommandConsumerGroupsByType(Map<String, String> commandConsumerGroupsByType) {
        this.commandConsumerGroupsByType = copyMap(commandConsumerGroupsByType);
    }

    public Map<String, String> getCommandAuthorityPrincipalsByType() {
        return commandAuthorityPrincipalsByType;
    }

    public void setCommandAuthorityPrincipalsByType(Map<String, String> commandAuthorityPrincipalsByType) {
        this.commandAuthorityPrincipalsByType = copyMap(commandAuthorityPrincipalsByType);
    }

    public Map<String, String> getCommandPartitionCountsByType() {
        return commandPartitionCountsByType;
    }

    public void setCommandPartitionCountsByType(Map<String, String> commandPartitionCountsByType) {
        this.commandPartitionCountsByType = copyMap(commandPartitionCountsByType);
    }

    public Map<String, String> getCommandTopicsByType() {
        return commandTopicsByType;
    }

    public void setCommandTopicsByType(Map<String, String> commandTopicsByType) {
        this.commandTopicsByType = copyMap(commandTopicsByType);
    }

    public Map<String, String> getCommandResponseTopicsByType() {
        return commandResponseTopicsByType;
    }

    public void setCommandResponseTopicsByType(Map<String, String> commandResponseTopicsByType) {
        this.commandResponseTopicsByType = copyMap(commandResponseTopicsByType);
    }

    public Map<String, String> getCommandEventTopicsByType() {
        return commandEventTopicsByType;
    }

    public void setCommandEventTopicsByType(Map<String, String> commandEventTopicsByType) {
        this.commandEventTopicsByType = copyMap(commandEventTopicsByType);
    }

    public Map<String, String> getCommandStateTopicsByType() {
        return commandStateTopicsByType;
    }

    public void setCommandStateTopicsByType(Map<String, String> commandStateTopicsByType) {
        this.commandStateTopicsByType = copyMap(commandStateTopicsByType);
    }

    public Map<String, String> getCommandLogStoresByType() {
        return commandLogStoresByType;
    }

    public void setCommandLogStoresByType(Map<String, String> commandLogStoresByType) {
        this.commandLogStoresByType = copyMap(commandLogStoresByType);
    }

    public Map<String, String> getCommandHotProjectionStoresByType() {
        return commandHotProjectionStoresByType;
    }

    public void setCommandHotProjectionStoresByType(Map<String, String> commandHotProjectionStoresByType) {
        this.commandHotProjectionStoresByType = copyMap(commandHotProjectionStoresByType);
    }

    public Map<String, String> getCommandHistoryStoresByType() {
        return commandHistoryStoresByType;
    }

    public void setCommandHistoryStoresByType(Map<String, String> commandHistoryStoresByType) {
        this.commandHistoryStoresByType = copyMap(commandHistoryStoresByType);
    }

    public Map<String, String> getCommandCacheStoresByType() {
        return commandCacheStoresByType;
    }

    public void setCommandCacheStoresByType(Map<String, String> commandCacheStoresByType) {
        this.commandCacheStoresByType = copyMap(commandCacheStoresByType);
    }

    public Map<String, String> getReadProjectionFamiliesByType() {
        return readProjectionFamiliesByType;
    }

    public void setReadProjectionFamiliesByType(Map<String, String> readProjectionFamiliesByType) {
        this.readProjectionFamiliesByType = copyMap(readProjectionFamiliesByType);
    }

    public Map<String, String> getReadServingStoresByType() {
        return readServingStoresByType;
    }

    public void setReadServingStoresByType(Map<String, String> readServingStoresByType) {
        this.readServingStoresByType = copyMap(readServingStoresByType);
    }

    public Map<String, String> getReadCacheStoresByType() {
        return readCacheStoresByType;
    }

    public void setReadCacheStoresByType(Map<String, String> readCacheStoresByType) {
        this.readCacheStoresByType = copyMap(readCacheStoresByType);
    }

    public String getManifestFingerprint() {
        return manifestFingerprint;
    }

    public void setManifestFingerprint(String manifestFingerprint) {
        this.manifestFingerprint = manifestFingerprint;
    }

    public String summary() {
        return "nodeKind=" + nodeKind
            + ", manifestVersion=" + manifestVersion
            + ", authorityServerId=" + authorityServerId
            + ", runtimeDataMode=" + runtimeDataMode
            + ", commandRouteManifest=" + shortFingerprint(commandRouteManifestFingerprint)
            + ", authorityDomainTopology=" + shortFingerprint(authorityDomainTopologyFingerprint)
            + ", manifestFingerprint=" + shortFingerprint(manifestFingerprint);
    }

    @Override
    public String toString() {
        return "RuntimeAuthorityDeliveryManifest[" + summary() + "]";
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
    }

    private static void appendMap(StringBuilder material, String name, Map<String, String> values) {
        material.append(name).append('=');
        TreeMap<String, String> sorted = new TreeMap<>();
        if (values != null) {
            sorted.putAll(values);
        }
        sorted.forEach((key, value) -> material
            .append(key)
            .append(':')
            .append(value(value))
            .append(';'));
        material.append('\n');
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }
}
