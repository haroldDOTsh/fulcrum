package sh.harold.fulcrum.fundamentals.rank;

import sh.harold.fulcrum.api.data.backend.core.AutoTableSchema;
import sh.harold.fulcrum.api.data.dirty.DirtyDataManager;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.query.QueryFilter;
import sh.harold.fulcrum.api.data.query.SortOrder;
import sh.harold.fulcrum.api.data.registry.PlayerProfile;
import sh.harold.fulcrum.api.data.registry.PlayerProfileManager;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;
import sh.harold.fulcrum.api.rank.enums.RankPriority;
import sh.harold.fulcrum.api.rank.model.EffectiveRank;
import sh.harold.fulcrum.api.rank.model.MonthlyRankData;
import sh.harold.fulcrum.api.rank.model.MonthlyRankHistoryData;
import sh.harold.fulcrum.fundamentals.identity.IdentityData;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Core implementation of rank logic and calculations.
 * Handles rank storage, retrieval, and effective rank calculation.
 */
public class RankManager implements RankService {

    private static final Logger LOGGER = Logger.getLogger(RankManager.class.getName());
    private final AutoTableSchema<MonthlyRankHistoryData> monthlyRankSchema = new AutoTableSchema<>(MonthlyRankHistoryData.class);
    private final AutoTableSchema<MonthlyRankData> monthlyRankDataSchema = new AutoTableSchema<>(MonthlyRankData.class);

    @Override
    public CompletableFuture<EffectiveRank> getEffectiveRank(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerProfile profile = PlayerProfileManager.get(playerId);
            if (profile == null) {
                profile = PlayerProfileManager.load(playerId);
            }

            IdentityData identity = profile.get(IdentityData.class);

            // Get active monthly rank from denormalized field (for performance)
            MonthlyPackageRank activeMonthly = identity.monthlyPackageRank;

            // Determine effective priority
            RankPriority priority = RankPriority.getEffectivePriority(
                    activeMonthly != null,
                    identity.functionalRank != null
            );

            // Calculate effective display and permissions
            String displayName;
            String colorCode;
            Set<String> permissions = new HashSet<>();

            switch (priority) {
                case MONTHLY_PACKAGE -> {
                    displayName = activeMonthly.getDisplayName();
                    colorCode = activeMonthly.getColorCode();
                    permissions.addAll(getMonthlyRankPermissions(activeMonthly));
                }
                case FUNCTIONAL -> {
                    displayName = identity.functionalRank.getDisplayName();
                    colorCode = identity.functionalRank.getColorCode();
                    permissions.addAll(getFunctionalRankPermissions(identity.functionalRank));
                }
                case PACKAGE -> {
                    displayName = identity.packageRank.getDisplayName();
                    colorCode = identity.packageRank.getColorCode();
                    permissions.addAll(getPackageRankPermissions(identity.packageRank));
                }
                default -> {
                    displayName = PackageRank.DEFAULT.getDisplayName();
                    colorCode = PackageRank.DEFAULT.getColorCode();
                    permissions.addAll(getPackageRankPermissions(PackageRank.DEFAULT));
                }
            }

            return new EffectiveRank(
                    identity.functionalRank,
                    identity.packageRank,
                    activeMonthly,
                    priority,
                    displayName,
                    colorCode,
                    permissions
            );
        });
    }

    @Override
    public CompletableFuture<FunctionalRank> getFunctionalRank(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerProfile profile = getPlayerProfile(playerId);
            IdentityData identity = profile.get(IdentityData.class);
            return identity.functionalRank;
        });
    }

    @Override
    public CompletableFuture<PackageRank> getPackageRank(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerProfile profile = getPlayerProfile(playerId);
            IdentityData identity = profile.get(IdentityData.class);
            return identity.packageRank;
        });
    }

    @Override
    public CompletableFuture<MonthlyPackageRank> getMonthlyRank(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerProfile profile = getPlayerProfile(playerId);
            IdentityData identity = profile.get(IdentityData.class);
            return identity.monthlyPackageRank;
        });
    }

    // ===== RANK MODIFICATION =====

    @Override
    public CompletableFuture<Void> setFunctionalRank(UUID playerId, FunctionalRank rank) {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("[DIAGNOSTIC] RankManager.setFunctionalRank() called for player: " + playerId + ", rank: " + rank);

            // Check DirtyDataManager initialization status
            boolean dirtyManagerInitialized = DirtyDataManager.isInitialized();
            LOGGER.info("[DIAGNOSTIC] DirtyDataManager.isInitialized(): " + dirtyManagerInitialized);

            PlayerProfile profile = getPlayerProfile(playerId);
            IdentityData identity = profile.get(IdentityData.class);

            LOGGER.info("[DIAGNOSTIC] Retrieved IdentityData for player: " + playerId + ", current functional rank: " + identity.functionalRank);

            identity.functionalRank = rank;
            LOGGER.info("[DIAGNOSTIC] Updated functional rank in IdentityData to: " + rank);

            LOGGER.info("[DIAGNOSTIC] Calling profile.saveAsync() for IdentityData...");
            profile.saveAsync(IdentityData.class, identity);
            LOGGER.info("[DIAGNOSTIC] profile.saveAsync() call completed for IdentityData");
        });
    }

    @Override
    public CompletableFuture<Void> setPackageRank(UUID playerId, PackageRank rank) {
        return CompletableFuture.runAsync(() -> {
            PackageRank finalRank = rank == null ? PackageRank.DEFAULT : rank;

            PlayerProfile profile = getPlayerProfile(playerId);
            IdentityData identity = profile.get(IdentityData.class);
            identity.packageRank = finalRank;
            profile.saveAsync(IdentityData.class, identity);
        });
    }

    @Override
    public CompletableFuture<Void> grantMonthlyRank(UUID playerId, MonthlyPackageRank rank, Duration duration) {
        return CompletableFuture.runAsync(() -> {
            PlayerProfile profile = getPlayerProfile(playerId);

            System.out.println("[DEBUG] grantMonthlyRank called for player: " + playerId + ", rank: " + rank);

            // Create new historical record
            MonthlyRankHistoryData historyData = new MonthlyRankHistoryData();
            historyData.uuid = playerId;
            historyData.rank = rank;
            historyData.grantedAt = System.currentTimeMillis();
            historyData.expiresAt = System.currentTimeMillis() + duration.toMillis();
            historyData.grantedBy = "SYSTEM"; // Could be parameterized
            historyData.autoRenew = false;

            // Save to historical table
            System.out.println("[DEBUG] Saving MonthlyRankHistoryData to monthly_ranks_history table");
            profile.saveAsync(MonthlyRankHistoryData.class, historyData);

            // Create/update MonthlyRankData record for monthly_ranks table
            System.out.println("[DEBUG] Creating/updating MonthlyRankData record for monthly_ranks table");
            MonthlyRankData monthlyData = new MonthlyRankData();
            monthlyData.uuid = playerId;  // This will overwrite existing record due to PLAYER_UUID primary key
            monthlyData.rank = rank;
            monthlyData.grantedAt = System.currentTimeMillis();
            monthlyData.expiresAt = System.currentTimeMillis() + duration.toMillis();
            monthlyData.grantedBy = "SYSTEM";
            monthlyData.autoRenew = false;

            try {
                profile.saveAsync(MonthlyRankData.class, monthlyData);
                System.out.println("[DEBUG] Successfully saved MonthlyRankData to monthly_ranks table");
            } catch (Exception e) {
                System.err.println("[ERROR] Failed to save MonthlyRankData: " + e.getMessage());
                // Note: MonthlyRankHistoryData was already saved, so history is preserved
                // but current active rank table may be inconsistent
            }

            // Update denormalized field in identity for performance
            IdentityData identity = profile.get(IdentityData.class);
            identity.monthlyPackageRank = rank;
            profile.saveAsync(IdentityData.class, identity);
        });
    }

    @Override
    public CompletableFuture<Void> removeMonthlyRank(UUID playerId) {
        return getActiveMonthlyRankData(playerId).thenAccept(activeRank -> {
            if (activeRank != null) {
                PlayerProfile profile = getPlayerProfile(playerId);

                System.out.println("[DEBUG] removeMonthlyRank called for player: " + playerId);

                // Expire the current active rank in history table
                activeRank.expiresAt = System.currentTimeMillis();
                profile.saveAsync(MonthlyRankHistoryData.class, activeRank);
                System.out.println("[DEBUG] Expired MonthlyRankHistoryData record in monthly_ranks_history");

                // EDGE CASE: Also need to expire/remove MonthlyRankData record
                try {
                    // Get current MonthlyRankData record and expire it
                    MonthlyRankData currentData = new MonthlyRankData();
                    currentData.uuid = playerId;
                    currentData.rank = activeRank.rank;
                    currentData.grantedAt = activeRank.grantedAt;
                    currentData.expiresAt = System.currentTimeMillis(); // Set to expired
                    currentData.grantedBy = activeRank.grantedBy;
                    currentData.autoRenew = activeRank.autoRenew;

                    profile.saveAsync(MonthlyRankData.class, currentData);
                    System.out.println("[DEBUG] Expired MonthlyRankData record in monthly_ranks table");
                } catch (Exception e) {
                    System.err.println("[ERROR] Failed to expire MonthlyRankData: " + e.getMessage());
                    // History table was updated successfully, but current table may be inconsistent
                }

                // Update denormalized field
                IdentityData identity = profile.get(IdentityData.class);
                identity.monthlyPackageRank = null;
                profile.saveAsync(IdentityData.class, identity);
                System.out.println("[DEBUG] Cleared monthly rank from IdentityData");
            }
        });
    }

    // ===== PERMISSION CHECKING =====

    @Override
    public CompletableFuture<Boolean> hasPermission(UUID playerId, String permission) {
        return getEffectiveRank(playerId).thenApply(rank -> rank.hasPermission(permission));
    }

    @Override
    public CompletableFuture<Boolean> hasRankOrHigher(UUID playerId, PackageRank minimumRank) {
        return getPackageRank(playerId).thenApply(rank -> rank.hasRankOrHigher(minimumRank));
    }

    @Override
    public CompletableFuture<Boolean> hasFunctionalRankOrHigher(UUID playerId, FunctionalRank minimumRank) {
        return getFunctionalRank(playerId).thenApply(rank ->
                rank != null && rank.hasRankOrHigher(minimumRank));
    }

    // ===== UTILITY METHODS =====

    @Override
    public CompletableFuture<Void> expireMonthlyRanks() {
        return CrossSchemaQueryBuilder
                .from(monthlyRankSchema)
                .where(QueryFilter.lessThan("expiresAt", System.currentTimeMillis(), monthlyRankSchema))
                .orderBy("expiresAt", SortOrder.Direction.ASC)
                .executeAsync()
                .thenApply(results ->
                        results.stream()
                                .map(result -> result.<MonthlyRankHistoryData>getData(monthlyRankSchema))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList())
                )
                .thenAccept(expiredRanks -> {
                    for (MonthlyRankHistoryData expiredRank : expiredRanks) {
                        // Update the denormalized field in IdentityData to remove expired rank
                        PlayerProfile profile = getPlayerProfile(expiredRank.uuid);
                        IdentityData identity = profile.get(IdentityData.class);

                        // Only clear if this was the active rank
                        if (identity.monthlyPackageRank == expiredRank.rank) {
                            identity.monthlyPackageRank = null;
                            profile.saveAsync(IdentityData.class, identity);
                        }
                    }
                });
    }

    @Override
    public CompletableFuture<List<UUID>> getPlayersWithRank(PackageRank rank) {
        // Query IdentityData for players with specific PackageRank
        return CompletableFuture.supplyAsync(() -> {
            // This would typically use CrossSchemaQueryBuilder with IdentityData schema
            // For now, returning empty list as IdentityData schema implementation is not shown
            return new ArrayList<>();
            // TODO: Implement with IdentityData schema when available:
            // return CrossSchemaQueryBuilder
            //     .from(identityDataSchema)
            //     .where("packageRank", QueryFilter.equals("packageRank", rank, identityDataSchema).getPredicate())
            //     .executeAsync()
            //     .thenApply(results -> results.stream()
            //         .map(result -> result.getData(IdentityData.class).uuid)
            //         .collect(Collectors.toList()));
        });
    }

    @Override
    public CompletableFuture<List<UUID>> getPlayersWithFunctionalRank(FunctionalRank rank) {
        // Query IdentityData for players with specific FunctionalRank
        return CompletableFuture.supplyAsync(() -> {
            // This would typically use CrossSchemaQueryBuilder with IdentityData schema
            // For now, returning empty list as IdentityData schema implementation is not shown
            return new ArrayList<>();
            // TODO: Implement with IdentityData schema when available:
            // return CrossSchemaQueryBuilder
            //     .from(identityDataSchema)
            //     .where("functionalRank", QueryFilter.equals("functionalRank", rank, identityDataSchema).getPredicate())
            //     .executeAsync()
            //     .thenApply(results -> results.stream()
            //         .map(result -> result.getData(IdentityData.class).uuid)
            //         .collect(Collectors.toList()));
        });
    }

    @Override
    public CompletableFuture<List<UUID>> getPlayersWithActiveMonthlyRank() {
        // Query for all players with active monthly ranks
        return CrossSchemaQueryBuilder
                .from(monthlyRankSchema)
                .where(QueryFilter.greaterThan("expiresAt", System.currentTimeMillis(), monthlyRankSchema))
                .executeAsync()
                .thenApply(results ->
                        results.stream()
                                .map(result -> result.<MonthlyRankHistoryData>getData(monthlyRankSchema))
                                .filter(Objects::nonNull)
                                .map(data -> data.uuid)
                                .distinct()
                                .collect(Collectors.toList())
                );
    }

    // ===== HISTORICAL MONTHLY RANK METHODS =====

    @Override
    public CompletableFuture<List<MonthlyRankHistoryData>> getMonthlyRankHistory(UUID playerId) {
        // Query for all monthly rank history for a specific player
        return CrossSchemaQueryBuilder
                .from(monthlyRankSchema)
                .where(QueryFilter.equals("uuid", playerId, monthlyRankSchema))
                .orderBy("grantedAt", SortOrder.Direction.DESC)
                .executeAsync()
                .thenApply(results ->
                        results.stream()
                                .map(result -> result.<MonthlyRankHistoryData>getData(monthlyRankSchema))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList())
                );
    }

    @Override
    public CompletableFuture<MonthlyRankHistoryData> getActiveMonthlyRankData(UUID playerId) {
        // Query for current active monthly rank for a specific player
        return CrossSchemaQueryBuilder
                .from(monthlyRankSchema)
                .where(QueryFilter.equals("uuid", playerId, monthlyRankSchema))
                .where(QueryFilter.greaterThan("expiresAt", System.currentTimeMillis(), monthlyRankSchema))
                .orderBy("expiresAt", SortOrder.Direction.DESC)
                .limit(1)
                .executeAsync()
                .thenApply(results ->
                        results.stream()
                                .map(result -> result.<MonthlyRankHistoryData>getData(monthlyRankSchema))
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null)
                );
    }

    @Override
    public CompletableFuture<List<MonthlyRankHistoryData>> getExpiredMonthlyRanks(UUID playerId) {
        // Query for expired monthly ranks for a specific player
        return CrossSchemaQueryBuilder
                .from(monthlyRankSchema)
                .where(QueryFilter.equals("uuid", playerId, monthlyRankSchema))
                .where(QueryFilter.lessThan("expiresAt", System.currentTimeMillis(), monthlyRankSchema))
                .orderBy("expiresAt", SortOrder.Direction.DESC)
                .executeAsync()
                .thenApply(results ->
                        results.stream()
                                .map(result -> result.<MonthlyRankHistoryData>getData(monthlyRankSchema))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList())
                );
    }

    // ===== HELPER METHODS =====

    private PlayerProfile getPlayerProfile(UUID playerId) {
        PlayerProfile profile = PlayerProfileManager.get(playerId);
        if (profile == null) {
            profile = PlayerProfileManager.load(playerId);
        }
        return profile;
    }

    private Set<String> getFunctionalRankPermissions(FunctionalRank rank) {
        return switch (rank) {
            case ADMIN -> Set.of("*", "admin.*", "moderator.*");
            case MODERATOR -> Set.of("moderator.*", "kick", "ban", "mute");
        };
    }

    private Set<String> getPackageRankPermissions(PackageRank rank) {
        return switch (rank) {
            case DEFAULT -> Set.of("player.basic");
            case VIP -> Set.of("player.basic", "vip.*", "fly");
            case MVP -> Set.of("player.basic", "vip.*", "mvp.*", "fly", "gamemode");
            case YOUTUBER -> Set.of("player.basic", "vip.*", "youtuber.*", "fly");
        };
    }

    private Set<String> getMonthlyRankPermissions(MonthlyPackageRank rank) {
        return switch (rank) {
            case MVP_PLUS -> Set.of("player.basic", "vip.*", "mvp.*", "mvp+.*", "fly", "gamemode", "creative");
            case MVP_PLUS_PLUS ->
                    Set.of("player.basic", "vip.*", "mvp.*", "mvp+.*", "mvp++.*", "fly", "gamemode", "creative", "admin");
        };
    }
}