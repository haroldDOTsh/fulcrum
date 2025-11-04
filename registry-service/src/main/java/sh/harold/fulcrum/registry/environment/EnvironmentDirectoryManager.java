package sh.harold.fulcrum.registry.environment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDescriptorView;
import sh.harold.fulcrum.api.environment.directory.EnvironmentDirectoryView;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryResponseMessage;
import sh.harold.fulcrum.api.messagebus.messages.environment.EnvironmentDirectoryUpdatedMessage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class EnvironmentDirectoryManager implements Closeable {
    private final EnvironmentDirectoryRepository repository;
    private final EnvironmentDirectoryCache cache;
    private final MessageBus messageBus;
    private final Logger logger;
    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Map<String, EnvironmentDirectoryDocument> cachedDocuments = Map.of();
    private volatile EnvironmentDirectoryView cachedView = new EnvironmentDirectoryView(Map.of(), null);
    private MessageHandler requestHandler;

    public EnvironmentDirectoryManager(EnvironmentDirectoryRepository repository,
                                       EnvironmentDirectoryCache cache,
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
            refreshInternal(false);
            requestHandler = this::handleRequest;
            messageBus.subscribe(ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_REQUEST, requestHandler);
        } finally {
            lock.unlock();
        }
    }

    public List<EnvironmentDirectoryDocument> listEnvironments() {
        return new ArrayList<>(cachedDocuments.values());
    }

    public Optional<EnvironmentDirectoryDocument> getEnvironment(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cachedDocuments.get(id));
    }

    public EnvironmentDirectoryView refreshDirectory(boolean broadcast) {
        lock.lock();
        try {
            return refreshInternal(broadcast);
        } finally {
            lock.unlock();
        }
    }

    public EnvironmentDirectoryView getCachedView() {
        return cachedView;
    }

    @Override
    public void close() throws IOException {
        if (requestHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_REQUEST, requestHandler);
        }
        cache.close();
        repository.close();
    }

    private EnvironmentDirectoryView refreshInternal(boolean broadcast) {
        List<EnvironmentDirectoryDocument> documents = repository.loadAll();
        if (documents.isEmpty()) {
            documents = seedDefaultDirectory();
        }
        EnvironmentDirectoryView view = buildView(documents);
        cachedDocuments = toDocumentMap(documents);
        cachedView = view;

        if (cache.isAvailable()) {
            try {
                cache.store(view);
            } catch (Exception ex) {
                logger.warn("Failed to cache environment directory in Redis", ex);
            }
        }

        if (broadcast) {
            EnvironmentDirectoryUpdatedMessage message = new EnvironmentDirectoryUpdatedMessage(
                    Optional.ofNullable(view.revision()).orElse(UUID.randomUUID().toString())
            );
            messageBus.broadcast(ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_UPDATED, message);
        }

        return view;
    }

    private void handleRequest(MessageEnvelope envelope) {
        try {
            EnvironmentDirectoryRequestMessage request = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), EnvironmentDirectoryRequestMessage.class);
            if (request == null) {
                return;
            }
            processRequest(request, envelope.senderId());
        } catch (Exception ex) {
            logger.error("Failed to process environment directory request message", ex);
        }
    }

    private void processRequest(EnvironmentDirectoryRequestMessage request, String senderId) {
        if (senderId == null || senderId.isBlank()) {
            logger.warn("Received environment directory request {} without senderId", request.getRequestId());
            return;
        }

        EnvironmentDirectoryView view;
        if (request.isRefresh()) {
            view = refreshDirectory(false);
        } else {
            view = getCachedView();
        }

        EnvironmentDirectoryResponseMessage response = new EnvironmentDirectoryResponseMessage(
                Optional.ofNullable(request.getRequestId()).orElse(UUID.randomUUID()),
                true,
                null,
                view
        );

        messageBus.send(senderId, ChannelConstants.REGISTRY_ENVIRONMENT_DIRECTORY_RESPONSE, response);
    }

    private EnvironmentDirectoryView buildView(List<EnvironmentDirectoryDocument> documents) {
        Map<String, EnvironmentDescriptorView> descriptors = new LinkedHashMap<>();
        for (EnvironmentDirectoryDocument document : documents) {
            descriptors.put(document.id(), new EnvironmentDescriptorView(
                    document.id(),
                    document.tag(),
                    document.modules(),
                    document.description(),
                    document.minPlayers(),
                    document.maxPlayers(),
                    document.playerFactor(),
                    document.settings()
            ));
        }
        String revision = UUID.randomUUID().toString();
        return new EnvironmentDirectoryView(descriptors, revision);
    }

    private Map<String, EnvironmentDirectoryDocument> toDocumentMap(List<EnvironmentDirectoryDocument> documents) {
        Map<String, EnvironmentDirectoryDocument> map = new LinkedHashMap<>();
        for (EnvironmentDirectoryDocument document : documents) {
            map.put(document.id(), document);
        }
        return Map.copyOf(map);
    }

    private List<EnvironmentDirectoryDocument> seedDefaultDirectory() {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("environment-directory-defaults.json")) {
            if (input == null) {
                logger.warn("No environment directory defaults bundled; registry will start with an empty directory.");
                return List.of();
            }
            Map<String, EnvironmentDescriptorView> defaults = mapper.readValue(
                    input,
                    new TypeReference<Map<String, EnvironmentDescriptorView>>() {
                    }
            );
            List<EnvironmentDirectoryDocument> seeded = new ArrayList<>();
            defaults.forEach((id, descriptor) -> {
                EnvironmentDirectoryDocument doc = new EnvironmentDirectoryDocument(
                        id,
                        descriptor.tag(),
                        descriptor.modules(),
                        descriptor.description(),
                        descriptor.minPlayers(),
                        descriptor.maxPlayers(),
                        descriptor.playerFactor(),
                        descriptor.settings()
                );
                repository.save(doc);
                seeded.add(doc);
            });
            logger.info("Seeded {} environment definitions into MongoDB", seeded.size());
            return seeded;
        } catch (Exception ex) {
            logger.error("Failed to seed environment directory defaults", ex);
            return List.of();
        }
    }
}
