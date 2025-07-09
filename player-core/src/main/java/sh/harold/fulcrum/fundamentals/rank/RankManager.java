package sh.harold.fulcrum.fundamentals.rank;

import sh.harold.fulcrum.api.data.registry.PlayerProfile;
import sh.harold.fulcrum.api.data.registry.PlayerProfileManager;
import sh.harold.fulcrum.fundamentals.identity.IdentityData;
import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;
import sh.harold.fulcrum.api.rank.enums.RankPriority;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.model.EffectiveRank;
import sh.harold.fulcrum.api.rank.model.MonthlyRankData;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Core implementation of rank logic and calculations.
 * Handles rank storage, retrieval, and effective rank calculation.
 */
public class RankManager implements RankService {


    @Override
    public CompletableFuture<EffectiveRank> getEffectiveRank(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerProfile profile = PlayerProfileManager.get(playerId);
            if (profile == null) {
                profile = PlayerProfileManager.load(playerId);
            }

            IdentityData identity = profile.get(IdentityData.class);
            MonthlyRankData monthlyData = profile.get(MonthlyRankData.class);

            // Check if monthly rank is active
            MonthlyPackageRank activeMonthly = null;
            if (monthlyData != null && monthlyData.isActive()) {
                activeMonthly = monthlyData.rank;
            }

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
            MonthlyRankData monthlyData = profile.get(MonthlyRankData.class);
            
            if (monthlyData != null && monthlyData.isActive()) {
                return monthlyData.rank;
            }
            return null;
        });
    }

    // ===== RANK MODIFICATION =====

    @Override
    public CompletableFuture<Void> setFunctionalRank(UUID playerId, FunctionalRank rank) {
        return CompletableFuture.runAsync(() -> {
            PlayerProfile profile = getPlayerProfile(playerId);
            IdentityData identity = profile.get(IdentityData.class);
            identity.functionalRank = rank;
            profile.saveAsync(IdentityData.class, identity);
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
            MonthlyRankData monthlyData = profile.get(MonthlyRankData.class, () -> new MonthlyRankData());
            
            monthlyData.uuid = playerId;
            monthlyData.rank = rank;
            monthlyData.grantedAt = System.currentTimeMillis();
            monthlyData.expiresAt = System.currentTimeMillis() + duration.toMillis();
            monthlyData.grantedBy = "SYSTEM"; // Could be parameterized
            monthlyData.autoRenew = false;
            
            profile.saveAsync(MonthlyRankData.class, monthlyData);
        });
    }

    @Override
    public CompletableFuture<Void> removeMonthlyRank(UUID playerId) {
        return CompletableFuture.runAsync(() -> {
            PlayerProfile profile = getPlayerProfile(playerId);
            MonthlyRankData monthlyData = profile.get(MonthlyRankData.class);
            
            if (monthlyData != null) {
                monthlyData.expiresAt = System.currentTimeMillis(); // Set to expired
                profile.saveAsync(MonthlyRankData.class, monthlyData);
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
        return CompletableFuture.runAsync(() -> {
            // TODO: Implement bulk expiration logic
            // This would need to query all monthly rank data and expire outdated ones
        });
    }

    @Override
    public CompletableFuture<List<UUID>> getPlayersWithRank(PackageRank rank) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: Implement database query for players with specific rank
            return new ArrayList<>();
        });
    }

    @Override
    public CompletableFuture<List<UUID>> getPlayersWithFunctionalRank(FunctionalRank rank) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: Implement database query for players with specific functional rank
            return new ArrayList<>();
        });
    }

    @Override
    public CompletableFuture<List<UUID>> getPlayersWithActiveMonthlyRank() {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: Implement database query for players with active monthly ranks
            return new ArrayList<>();
        });
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
            case MVP_PLUS_PLUS -> Set.of("player.basic", "vip.*", "mvp.*", "mvp+.*", "mvp++.*", "fly", "gamemode", "creative", "admin");
        };
    }
}