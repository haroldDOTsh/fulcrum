package sh.harold.fulcrum.velocity.rank;

import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.RankStateResolver;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Proxy-side {@link RankService} backed by the session cache and DataAPI.
 * Mutating operations are not supported on Velocity and will fail fast.
 */
public final class VelocityRankService implements RankService {
    private final VelocityPlayerSessionService sessionService;
    private final DataAPI dataAPI;
    private final Logger logger;

    public VelocityRankService(VelocityPlayerSessionService sessionService,
                               DataAPI dataAPI,
                               Logger logger) {
        this.sessionService = sessionService;
        this.dataAPI = dataAPI;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Rank> getPrimaryRank(UUID playerId) {
        return CompletableFuture.completedFuture(resolveSnapshot(playerId).primary());
    }

    @Override
    public Rank getPrimaryRankSync(UUID playerId) {
        return resolveSnapshot(playerId).primary();
    }

    @Override
    public CompletableFuture<Set<Rank>> getAllRanks(UUID playerId) {
        return CompletableFuture.completedFuture(resolveSnapshot(playerId).all());
    }

    @Override
    public CompletableFuture<Void> setPrimaryRank(UUID playerId, Rank rank, RankChangeContext context) {
        return unsupportedMutation("setPrimaryRank");
    }

    @Override
    public CompletableFuture<Void> addRank(UUID playerId, Rank rank, RankChangeContext context) {
        return unsupportedMutation("addRank");
    }

    @Override
    public CompletableFuture<Void> removeRank(UUID playerId, Rank rank, RankChangeContext context) {
        return unsupportedMutation("removeRank");
    }

    @Override
    public CompletableFuture<Rank> getEffectiveRank(UUID playerId) {
        return CompletableFuture.completedFuture(resolveSnapshot(playerId).effective());
    }

    @Override
    public Rank getEffectiveRankSync(UUID playerId) {
        return resolveSnapshot(playerId).effective();
    }

    @Override
    public CompletableFuture<Boolean> hasRank(UUID playerId, Rank rank) {
        Objects.requireNonNull(rank, "rank");
        return getAllRanks(playerId).thenApply(ranks -> ranks.contains(rank));
    }

    @Override
    public CompletableFuture<Boolean> isStaff(UUID playerId) {
        return getAllRanks(playerId).thenApply(ranks -> ranks.stream().anyMatch(Rank::isStaff));
    }

    @Override
    public CompletableFuture<Void> resetRanks(UUID playerId, RankChangeContext context) {
        return unsupportedMutation("resetRanks");
    }

    private CompletableFuture<Void> unsupportedMutation(String operation) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("VelocityRankService does not support " + operation));
    }

    private RankSnapshot resolveSnapshot(UUID playerId) {
        RankSnapshot fromSession = sessionService != null ? resolveFromSession(playerId) : RankSnapshot.empty();
        if (fromSession.hasData()) {
            return fromSession;
        }

        RankSnapshot fromDocument = resolveFromDocument(playerId);
        return fromDocument.hasData() ? fromDocument : RankSnapshot.defaultSnapshot();
    }

    private RankSnapshot resolveFromSession(UUID playerId) {
        return sessionService.getSession(playerId)
                .map(record -> {
                    Rank primary = RankStateResolver.resolve(record).orElse(null);
                    Set<Rank> ranks = parseRankCollection(record.getRank().get("all"));
                    if (ranks.isEmpty() && primary != null) {
                        ranks.add(primary);
                    }
                    return RankSnapshot.of(primary, ranks);
                })
                .orElse(RankSnapshot.empty());
    }

    private RankSnapshot resolveFromDocument(UUID playerId) {
        if (dataAPI == null) {
            return RankSnapshot.empty();
        }
        try {
            Document document = dataAPI.collection("players")
                    .document(playerId.toString());
            if (document == null || !document.exists()) {
                return RankSnapshot.empty();
            }

            Optional<Rank> primary = RankStateResolver.resolve(document);
            Object stored = document.get("rankInfo.all", null);
            Set<Rank> ranks = parseRankCollection(stored);
            if (ranks.isEmpty() && primary.isPresent()) {
                ranks.add(primary.get());
            }
            return RankSnapshot.of(primary.orElse(null), ranks);
        } catch (Exception ex) {
            logger.debug("Failed to resolve ranks for {}", playerId, ex);
            return RankSnapshot.empty();
        }
    }

    private Set<Rank> parseRankCollection(Object value) {
        Set<Rank> ranks = new LinkedHashSet<>();
        if (value instanceof Rank rank) {
            ranks.add(rank);
            return ranks;
        }
        if (value instanceof String single) {
            RankStateResolver.resolve(single).ifPresent(ranks::add);
            return ranks;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element instanceof Rank enumRank) {
                    ranks.add(enumRank);
                    continue;
                }
                if (element instanceof String text) {
                    RankStateResolver.resolve(text).ifPresent(ranks::add);
                }
            }
        }
        return ranks;
    }

    private record RankSnapshot(Rank primary, Set<Rank> all) {
        static RankSnapshot of(Rank primary, Set<Rank> all) {
            Set<Rank> copy = all == null ? new LinkedHashSet<>() : new LinkedHashSet<>(all);
            return new RankSnapshot(primary, copy);
        }

        static RankSnapshot empty() {
            return new RankSnapshot(null, Set.of());
        }

        static RankSnapshot defaultSnapshot() {
            return new RankSnapshot(Rank.DEFAULT, Set.of(Rank.DEFAULT));
        }

        Rank effective() {
            if (all == null || all.isEmpty()) {
                return primary != null ? primary : Rank.DEFAULT;
            }
            Rank highest = Rank.getHighestPriority(all.toArray(new Rank[0]));
            if (primary != null && primary.getPriority() > highest.getPriority()) {
                return primary;
            }
            return highest;
        }

        boolean hasData() {
            return primary != null || (all != null && !all.isEmpty());
        }
    }
}
