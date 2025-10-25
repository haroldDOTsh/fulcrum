package sh.harold.fulcrum.registry.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigResponseMessage;
import sh.harold.fulcrum.api.messagebus.messages.network.NetworkConfigUpdatedMessage;
import sh.harold.fulcrum.api.network.NetworkProfileView;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class NetworkConfigManager implements AutoCloseable {
    private final NetworkConfigRepository repository;
    private final NetworkConfigCache cache;
    private final MessageBus messageBus;
    private final Logger logger;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile List<NetworkProfileDocument> cachedProfiles = List.of();
    private volatile NetworkProfileDocument activeProfile;
    private volatile NetworkProfileView activeProfileView;
    private MessageHandler requestHandler;

    public NetworkConfigManager(NetworkConfigRepository repository,
                                NetworkConfigCache cache,
                                MessageBus messageBus,
                                Logger logger) {
        this.repository = repository;
        this.cache = cache;
        this.messageBus = messageBus;
        this.logger = logger;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void initialize() {
        lock.lock();
        try {
            refreshProfilesInternal();

            Optional<NetworkConfigRepository.ActiveProfileMarker> marker = repository.loadActiveMarker();
            NetworkProfileDocument profile = marker.flatMap(m -> findProfile(m.profileId())).orElse(null);

            if (profile == null) {
                profile = seedDefaultProfileIfMissing();
            }

            if (profile == null) {
                profile = findDefaultProfile();
            }

            if (profile == null) {
                logger.warn("No network settings profiles were found in MongoDB. Runtime nodes will fall back to local defaults.");
                return;
            }

            NetworkProfileValidator.validate(profile);
            setActiveProfile(profile, false);
            logger.info("Network profile '{}' ({}) loaded into Redis cache", profile.profileId(), profile.tag());

            requestHandler = this::handleRequest;
            messageBus.subscribe(ChannelConstants.REGISTRY_NETWORK_CONFIG_REQUEST, requestHandler);
        } finally {
            lock.unlock();
        }
    }

    public List<NetworkProfileSummary> listProfiles() {
        return cachedProfiles.stream()
                .map(profile -> new NetworkProfileSummary(
                        profile.profileId(),
                        profile.tag(),
                        profile.updatedAt(),
                        activeProfile != null && activeProfile.profileId().equals(profile.profileId())))
                .toList();
    }

    public NetworkProfileView applyProfile(String profileId) {
        lock.lock();
        try {
            NetworkProfileDocument profile = repository.loadProfile(profileId)
                    .orElseThrow(() -> new IllegalArgumentException("Profile '" + profileId + "' not found"));
            NetworkProfileValidator.validate(profile);

            Instant appliedAt = Instant.now();
            NetworkProfileDocument updated = profile.withUpdatedAt(appliedAt);

            repository.updateProfileTimestamp(updated.profileId(), appliedAt);
            repository.updateActiveMarker(updated.profileId(), updated.tag(), appliedAt);

            setActiveProfile(updated, true);
            refreshProfilesInternal();
            logger.info("Applied network profile '{}' ({})", updated.profileId(), updated.tag());
            return activeProfileView;
        } finally {
            lock.unlock();
        }
    }

    public void refreshProfiles() {
        lock.lock();
        try {
            refreshProfilesInternal();
            if (activeProfile != null) {
                repository.loadProfile(activeProfile.profileId())
                        .ifPresent(profile -> {
                            try {
                                NetworkProfileValidator.validate(profile);
                                setActiveProfile(profile, false);
                            } catch (NetworkProfileValidationException ex) {
                                logger.warn("Active profile '{}' failed validation during refresh: {}", profile.profileId(), ex.getErrors());
                            }
                        });
            }
        } finally {
            lock.unlock();
        }
    }

    public Optional<NetworkProfileView> getActiveProfileView() {
        return Optional.ofNullable(activeProfileView);
    }

    @Override
    public void close() throws IOException {
        if (requestHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_NETWORK_CONFIG_REQUEST, requestHandler);
        }
        cache.close();
        repository.close();
    }

    private void setActiveProfile(NetworkProfileDocument profile, boolean broadcast) {
        NetworkProfileView view = profile.toView();
        if (!cache.isAvailable()) {
            throw new IllegalStateException("Redis unavailable when attempting to update network config cache");
        }

        cache.storeProfile(view);
        activeProfile = profile;
        activeProfileView = view;

        if (broadcast) {
            NetworkConfigUpdatedMessage message = new NetworkConfigUpdatedMessage(
                    profile.profileId(),
                    profile.tag(),
                    profile.updatedAt()
            );
            messageBus.broadcast(ChannelConstants.REGISTRY_NETWORK_CONFIG_UPDATED, message);
        }
    }

    private void refreshProfilesInternal() {
        cachedProfiles = repository.loadProfiles();
    }

    private NetworkProfileDocument findDefaultProfile() {
        return findProfile("default").orElseGet(() -> cachedProfiles.isEmpty() ? null : cachedProfiles.get(0));
    }

    private Optional<NetworkProfileDocument> findProfile(String profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return cachedProfiles.stream()
                .filter(profile -> profile.profileId().equalsIgnoreCase(profileId))
                .findFirst();
    }

    private NetworkProfileDocument seedDefaultProfileIfMissing() {
        if (!cachedProfiles.isEmpty()) {
            return null;
        }

        try (InputStream input = getClass().getClassLoader().getResourceAsStream("network-config-defaults.json")) {
            if (input == null) {
                logger.warn("Unable to seed network configuration; missing network-config-defaults.json resource");
                return null;
            }

            NetworkProfileView view = mapper.readValue(input, NetworkProfileView.class);
            Instant updatedAt = view.updatedAt() != null ? view.updatedAt() : Instant.now();

            Map<String, NetworkProfileDocument.RankVisualDocument> ranks = new LinkedHashMap<>();
            view.ranks().forEach((rankId, visual) -> ranks.put(rankId,
                    new NetworkProfileDocument.RankVisualDocument(
                            visual.displayName(),
                            visual.colorCode(),
                            visual.fullPrefix(),
                            visual.shortPrefix(),
                            visual.nameColor()
                    )));

            NetworkProfileDocument document = new NetworkProfileDocument(
                    view.profileId(),
                    view.tag(),
                    view.serverIp(),
                    view.motd(),
                    view.scoreboard().title(),
                    view.scoreboard().footer(),
                    ranks,
                    updatedAt,
                    view.data()
            );

            NetworkProfileValidator.validate(document);
            repository.saveProfile(document);
            repository.updateActiveMarker(document.profileId(), document.tag(), updatedAt);

            refreshProfilesInternal();
            logger.info("Seeded default network profile '{}' ({}) into MongoDB", document.profileId(), document.tag());
            return findProfile(document.profileId()).orElse(document);
        } catch (NetworkProfileValidationException ex) {
            logger.error("Bundled network configuration defaults failed validation: {}", ex.getErrors());
            return null;
        } catch (IOException ex) {
            logger.error("Failed to read bundled network configuration defaults", ex);
            return null;
        } catch (Exception ex) {
            logger.error("Unexpected error while seeding network configuration defaults", ex);
            return null;
        }
    }

    private void handleRequest(MessageEnvelope envelope) {
        try {
            NetworkConfigRequestMessage request = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), NetworkConfigRequestMessage.class);
            if (request == null) {
                return;
            }
            processRequest(request, envelope.senderId());
        } catch (Exception ex) {
            logger.error("Failed to process network config request message", ex);
        }
    }

    private void processRequest(NetworkConfigRequestMessage request, String senderId) {
        if (senderId == null || senderId.isBlank()) {
            logger.warn("Received network config request {} without senderId", request.getRequestId());
            return;
        }

        NetworkProfileView view;
        List<String> errors = new ArrayList<>();

        lock.lock();
        try {
            if (request.isRefresh()) {
                refreshProfilesInternal();
            }

            String profileId = request.getProfileId();
            NetworkProfileDocument profile;

            if (profileId == null || profileId.isBlank()) {
                profile = activeProfile;
                if (profile == null) {
                    errors.add("No active profile is available");
                }
            } else {
                profile = repository.loadProfile(profileId).orElse(null);
                if (profile == null) {
                    errors.add("Profile '" + profileId + "' not found");
                } else {
                    try {
                        NetworkProfileValidator.validate(profile);
                    } catch (NetworkProfileValidationException ex) {
                        errors.addAll(ex.getErrors());
                    }
                }
            }

            if (errors.isEmpty() && profile != null) {
                boolean refreshActive = request.isRefresh() && (profileId == null || profileId.isBlank()
                        || (activeProfile != null && activeProfile.profileId().equalsIgnoreCase(profile.profileId())));
                if (refreshActive) {
                    repository.updateActiveMarker(profile.profileId(), profile.tag(), profile.updatedAt());
                    setActiveProfile(profile, false);
                }
                view = profile.toView();
            } else {
                view = null;
            }
        } finally {
            lock.unlock();
        }

        NetworkConfigResponseMessage response = new NetworkConfigResponseMessage(
                Optional.ofNullable(request.getRequestId()).orElse(UUID.randomUUID()),
                errors.isEmpty(),
                errors.isEmpty() ? null : String.join("; ", errors),
                view
        );

        messageBus.send(senderId, ChannelConstants.REGISTRY_NETWORK_CONFIG_RESPONSE, response);
    }

    public record NetworkProfileSummary(String profileId, String tag, Instant updatedAt, boolean active) {
    }
}
