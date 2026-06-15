package sh.harold.fulcrum.registry.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityDomainTopology;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusAdapter;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;
import sh.harold.fulcrum.api.messagebus.impl.InMemoryMessageBus;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.messages.RegistrationRequest;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationHandlerAuthorityDeliveryManifestTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void registrationFailsClosedWhenDeliveryManifestIsMissing() {
        Fixture fixture = new Fixture();
        try {
            AtomicReference<Map<String, Object>> response = fixture.captureServerRegistrationResponses();

            fixture.messageBus.broadcast(ChannelConstants.REGISTRY_REGISTRATION_REQUEST, registrationRequest());

            assertThat(response.get())
                .containsEntry("tempId", "temp-mini")
                .containsEntry("success", false)
                .containsEntry("message", "Data Authority delivery manifest is required");
            assertThat(fixture.serverRegistry.getAllServers()).isEmpty();
        } finally {
            fixture.close();
        }
    }

    @Test
    void registrationFailsClosedWhenDeliveryManifestOmitsPartitionKeyVectors() {
        Fixture fixture = new Fixture();
        try {
            AtomicReference<Map<String, Object>> response = fixture.captureServerRegistrationResponses();

            RegistrationRequest request = registrationRequest();
            request.setAuthorityDeliveryManifest(legacyManifestWithoutPartitionVectors());

            fixture.messageBus.broadcast(ChannelConstants.REGISTRY_REGISTRATION_REQUEST, request);

            assertThat(response.get())
                .containsEntry("tempId", "temp-mini")
                .containsEntry("success", false)
                .containsEntry("message", "Data Authority command partition-key vector manifest mismatch");
            assertThat(fixture.serverRegistry.getAllServers()).isEmpty();
        } finally {
            fixture.close();
        }
    }

    @Test
    void heartbeatManifestUpdateIgnoresInvalidManifest() {
        Fixture fixture = new Fixture();
        try {
            AtomicReference<Map<String, Object>> response = fixture.captureServerRegistrationResponses();

            RegistrationRequest request = registrationRequest();
            RuntimeAuthorityDeliveryManifest validManifest = validManifest();
            request.setAuthorityDeliveryManifest(validManifest);

            fixture.messageBus.broadcast(ChannelConstants.REGISTRY_REGISTRATION_REQUEST, request);

            String serverId = (String) response.get().get("assignedServerId");
            RegisteredServerData registered = fixture.serverRegistry.getServer(serverId);
            assertThat(registered.getAuthorityDeliveryManifest().getManifestFingerprint())
                .isEqualTo(validManifest.getManifestFingerprint());

            RuntimeAuthorityDeliveryManifest tamperedManifest = validManifest();
            tamperedManifest.setManifestFingerprint("tampered");
            fixture.messageBus.broadcast(ChannelConstants.SERVER_HEARTBEAT, Map.of(
                "serverId", serverId,
                "serverType", "mini",
                "playerCount", 0,
                "tps", 20.0,
                "authorityDeliveryManifest", tamperedManifest
            ));

            assertThat(fixture.serverRegistry.getServer(serverId)
                .getAuthorityDeliveryManifest()
                .getManifestFingerprint())
                .isEqualTo(validManifest.getManifestFingerprint());
        } finally {
            fixture.close();
        }
    }

    private static RegistrationRequest registrationRequest() {
        RegistrationRequest request = new RegistrationRequest();
        request.setTempId("temp-mini");
        request.setServerType("mini");
        request.setRole("default");
        request.setAddress("127.0.0.1");
        request.setPort(25565);
        request.setMaxCapacity(20);
        return request;
    }

    private static RuntimeAuthorityDeliveryManifest legacyManifestWithoutPartitionVectors() {
        return new RuntimeAuthorityDeliveryManifest(
            "Paper",
            1,
            "registry-service",
            "remote-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint",
            DataAuthority.COMMAND_SCHEMA_VERSION,
            AuthorityCommandManifest.fingerprint(),
            AuthorityCommandManifest.routeManifestFingerprint(),
            DataAuthorityReadContracts.schemaVersion(),
            DataAuthorityReadContracts.fingerprint(),
            commandDomainsByType(),
            commandDeliveryModesByType(),
            readProjectionFamiliesByType(),
            "legacy-manifest-fingerprint"
        );
    }

    private static RuntimeAuthorityDeliveryManifest validManifest() {
        return RuntimeAuthorityDeliveryManifest.create(
            "Paper",
            1,
            "registry-service",
            "remote-authority",
            "watermarked-snapshot-cache",
            "attestation-fingerprint",
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

    private static final class Fixture implements AutoCloseable {
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final IdAllocator idAllocator = new IdAllocator();
        private final ServerRegistry serverRegistry = new ServerRegistry(idAllocator);
        private final ProxyRegistry proxyRegistry = new ProxyRegistry(idAllocator);
        private final HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(serverRegistry, proxyRegistry, scheduler);
        private final InMemoryMessageBus messageBus = new InMemoryMessageBus(new TestAdapter("registry-service"));
        private final RegistrationHandler handler =
            new RegistrationHandler(serverRegistry, proxyRegistry, heartbeatMonitor);

        private Fixture() {
            handler.initialize(messageBus);
        }

        private AtomicReference<Map<String, Object>> captureServerRegistrationResponses() {
            AtomicReference<Map<String, Object>> response = new AtomicReference<>();
            messageBus.subscribe(ChannelConstants.SERVER_REGISTRATION_RESPONSE, envelope ->
                response.set(OBJECT_MAPPER.convertValue(
                    envelope.getPayload(),
                    new TypeReference<Map<String, Object>>() {
                    }
                ))
            );
            return response;
        }

        @Override
        public void close() {
            handler.shutdown();
            proxyRegistry.shutdown();
            scheduler.shutdownNow();
            messageBus.shutdown();
        }
    }

    private record TestAdapter(String serverId) implements MessageBusAdapter {
        @Override
        public String getServerId() {
            return serverId;
        }

        @Override
        public Executor getAsyncExecutor() {
            return Runnable::run;
        }

        @Override
        public Logger getLogger() {
            return Logger.getLogger(RegistrationHandlerAuthorityDeliveryManifestTest.class.getName());
        }

        @Override
        public MessageBusConnectionConfig getConnectionConfig() {
            return MessageBusConnectionConfig.builder()
                .type(MessageBusConnectionConfig.MessageBusType.IN_MEMORY)
                .build();
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}
