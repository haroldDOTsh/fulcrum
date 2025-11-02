package sh.harold.fulcrum.registry.rank;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.DocumentPatch;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.rank.RankMutationRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.rank.RankMutationResponseMessage;
import sh.harold.fulcrum.api.messagebus.messages.rank.RankSyncMessage;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;
import sh.harold.fulcrum.api.rank.RankMutationType;

import java.util.*;
import java.util.stream.Collectors;

public final class RankMutationService implements AutoCloseable {

    private static final String PLAYERS_COLLECTION = "players";

    private final MessageBus messageBus;
    private final Logger logger;
    private final MongoConnectionAdapter connectionAdapter;
    private final DataAPI dataAPI;
    private final Collection playersCollection;
    private final MessageHandler requestHandler;

    public RankMutationService(MessageBus messageBus,
                               Logger logger,
                               MongoConnectionAdapter connectionAdapter) {
        this.messageBus = messageBus;
        this.logger = logger;
        this.connectionAdapter = connectionAdapter;
        this.dataAPI = DataAPI.create(connectionAdapter);
        this.playersCollection = dataAPI.collection(PLAYERS_COLLECTION);

        this.requestHandler = this::handleRequest;
        this.messageBus.subscribe(ChannelConstants.REGISTRY_RANK_MUTATION_REQUEST, requestHandler);
        logger.info("RankMutationService subscribed to mutation requests (database='{}')", connectionAdapter.getDatabaseName());
    }

    private void handleRequest(MessageEnvelope envelope) {
        RankMutationRequestMessage request;
        try {
            request = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), RankMutationRequestMessage.class);
        } catch (Exception ex) {
            logger.warn("Failed to deserialize rank mutation request", ex);
            return;
        }
        if (request == null || request.getRequestId() == null || request.getPlayerId() == null) {
            return;
        }

        UUID playerId = request.getPlayerId();
        String senderId = envelope.senderId();
        if (senderId == null || senderId.isBlank()) {
            logger.warn("Ignoring rank mutation request {} without sender", request.getRequestId());
            return;
        }

        try {
            RankMutationResponseMessage response = processRequest(request);
            messageBus.send(senderId, ChannelConstants.REGISTRY_RANK_MUTATION_RESPONSE, response);

            if (response.isSuccess()) {
                RankSyncMessage sync = new RankSyncMessage(
                        playerId,
                        response.getPrimaryRankId(),
                        response.getRankIds());
                messageBus.broadcast(ChannelConstants.REGISTRY_RANK_UPDATE, sync);
            }
        } catch (Exception ex) {
            logger.warn("Failed to process rank mutation request {}", request.getRequestId(), ex);
            RankMutationResponseMessage failure = new RankMutationResponseMessage(
                    request.getRequestId(),
                    false,
                    ex.getMessage(),
                    null,
                    null
            );
            messageBus.send(senderId, ChannelConstants.REGISTRY_RANK_MUTATION_RESPONSE, failure);
        }
    }

    public RankMutationResponseMessage mutateDirect(UUID playerId,
                                                    RankMutationType mutationType,
                                                    Rank rank,
                                                    RankChangeContext context,
                                                    String playerName) {
        RankMutationRequestMessage request = new RankMutationRequestMessage(
                UUID.randomUUID(),
                playerId,
                mutationType,
                rank != null ? rank.name() : null,
                context != null ? context : RankChangeContext.system(),
                playerName
        );
        RankMutationResponseMessage response = processRequest(request);
        if (response.isSuccess()) {
            RankSyncMessage sync = new RankSyncMessage(playerId, response.getPrimaryRankId(), response.getRankIds());
            messageBus.broadcast(ChannelConstants.REGISTRY_RANK_UPDATE, sync);
        }
        return response;
    }

    public Optional<RankSnapshot> getRankSnapshot(UUID playerId) {
        RankState state = loadRankState(playerId);
        boolean documentExists = state.document != null && state.document.exists();
        if (!documentExists && state.ranks.isEmpty()) {
            return Optional.empty();
        }
        List<Rank> ordered = state.ranks.stream()
                .sorted(Comparator.comparingInt(Rank::getPriority).reversed())
                .collect(Collectors.toList());
        return Optional.of(new RankSnapshot(state.primary, ordered));
    }

    private RankMutationResponseMessage processRequest(RankMutationRequestMessage request) {
        RankMutationType mutationType = request.getMutationType();
        if (mutationType == null) {
            return new RankMutationResponseMessage(request.getRequestId(), false, "Missing mutation type", null, null);
        }

        RankState state = loadRankState(request.getPlayerId());
        LinkedHashSet<Rank> rankSet = state.ranks;
        Rank currentPrimary = state.primary;
        Document playerDoc = state.document;

        Rank targetRank = parseRank(request.getRankId());

        switch (mutationType) {
            case SET_PRIMARY -> {
                if (targetRank == null) {
                    return failure(request, "Rank is required for SET_PRIMARY");
                }
                rankSet.add(targetRank);
                currentPrimary = targetRank;
            }
            case ADD -> {
                if (targetRank == null) {
                    return failure(request, "Rank is required for ADD");
                }
                rankSet.add(targetRank);
                if (currentPrimary == null || currentPrimary == Rank.DEFAULT) {
                    currentPrimary = highestRank(rankSet);
                }
            }
            case REMOVE -> {
                if (targetRank == null) {
                    return failure(request, "Rank is required for REMOVE");
                }
                rankSet.remove(targetRank);
                if (rankSet.isEmpty()) {
                    currentPrimary = Rank.DEFAULT;
                } else if (Objects.equals(currentPrimary, targetRank)) {
                    currentPrimary = highestRank(rankSet);
                }
            }
            case RESET -> {
                rankSet.clear();
                currentPrimary = Rank.DEFAULT;
            }
            default -> {
            }
        }

        if (currentPrimary == null) {
            currentPrimary = Rank.DEFAULT;
        }
        rankSet.removeIf(Objects::isNull);
        rankSet.add(currentPrimary);

        applyPersistence(request, playerDoc, currentPrimary, rankSet);

        Set<String> rankIds = rankSet.stream().map(Rank::name).collect(Collectors.toCollection(LinkedHashSet::new));
        return new RankMutationResponseMessage(
                request.getRequestId(),
                true,
                null,
                currentPrimary.name(),
                rankIds
        );
    }

    private RankState loadRankState(UUID playerId) {
        Document playerDoc = playersCollection.document(playerId.toString());
        LinkedHashSet<Rank> ranks = new LinkedHashSet<>();
        Rank primary = Rank.DEFAULT;

        if (playerDoc.exists()) {
            Map<String, Object> data = playerDoc.toMap();

            Object rankInfo = data.get("rankInfo");
            if (rankInfo instanceof Map<?, ?> infoMap) {
                Object all = infoMap.get("all");
                if (all instanceof List<?> list) {
                    for (Object value : list) {
                        Rank parsed = parseRank(value);
                        if (parsed != null) {
                            ranks.add(parsed);
                        }
                    }
                }
                Rank primaryRank = parseRank(infoMap.get("primary"));
                if (primaryRank != null) {
                    primary = primaryRank;
                    ranks.add(primaryRank);
                }
            }

            Rank legacyPrimary = parseRank(data.get("rank"));
            if (legacyPrimary != null) {
                primary = legacyPrimary;
                ranks.add(legacyPrimary);
            }
        }

        return new RankState(playerDoc, ranks, primary);
    }

    private void applyPersistence(RankMutationRequestMessage request,
                                  Document playerDoc,
                                  Rank primary,
                                  LinkedHashSet<Rank> rankSet) {
        DocumentPatch.Builder patchBuilder = DocumentPatch.builder().upsert(true);
        if (request.getPlayerName() != null && !request.getPlayerName().isBlank()) {
            patchBuilder.setOnInsert("username", request.getPlayerName());
        }

        List<String> orderedRanks = rankSet.stream()
                .sorted(Comparator.comparingInt(Rank::getPriority).reversed())
                .map(Rank::name)
                .distinct()
                .collect(Collectors.toList());

        boolean hasNonDefault = orderedRanks.stream().anyMatch(name -> !Rank.DEFAULT.name().equalsIgnoreCase(name));
        if (!hasNonDefault && primary == Rank.DEFAULT) {
            patchBuilder.unset("rank");
            patchBuilder.unset("rankInfo");
        } else {
            patchBuilder.set("rank", primary.name());

            Map<String, Object> rankInfo = new LinkedHashMap<>();
            rankInfo.put("primary", primary.name());
            rankInfo.put("all", orderedRanks);

            if (request.getContext() != null && request.getContext().executorType() != RankChangeContext.Executor.SYSTEM) {
                rankInfo.put("updatedAt", System.currentTimeMillis());
                rankInfo.put("updatedBy", buildUpdatedBy(request.getContext()));
            }

            patchBuilder.set("rankInfo", rankInfo);
        }

        DocumentPatch patch = patchBuilder.build();
        playerDoc.patch(patch);
    }

    private Map<String, Object> buildUpdatedBy(RankChangeContext context) {
        Map<String, Object> updatedBy = new LinkedHashMap<>();
        updatedBy.put("type", context.executorType().name());
        updatedBy.put("name", context.executorName());
        if (context.executorUuid() != null) {
            updatedBy.put("uuid", context.executorUuid().toString());
        }
        return updatedBy;
    }

    private Rank highestRank(Set<Rank> ranks) {
        return ranks.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(Rank::getPriority))
                .orElse(Rank.DEFAULT);
    }

    private Rank parseRank(Object value) {
        if (value == null) {
            return null;
        }
        String name = value.toString().trim();
        if (name.isEmpty()) {
            return null;
        }
        try {
            return Rank.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private RankMutationResponseMessage failure(RankMutationRequestMessage request, String error) {
        return new RankMutationResponseMessage(request.getRequestId(), false, error, null, null);
    }

    @Override
    public void close() {
        messageBus.unsubscribe(ChannelConstants.REGISTRY_RANK_MUTATION_REQUEST, requestHandler);
    }

    public record RankSnapshot(Rank primary, List<Rank> ranks) {
    }

    private record RankState(Document document, LinkedHashSet<Rank> ranks, Rank primary) {
    }
}
