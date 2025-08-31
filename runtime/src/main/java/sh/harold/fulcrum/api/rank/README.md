# Rank API

Unified rank system with priority-based resolution and expiration support.

## Rank Structure

```java
public enum Rank {
    // Player ranks
    DEFAULT(1, RankCategory.PLAYER, NamedTextColor.GRAY),
    PLAYER(2, RankCategory.PLAYER, NamedTextColor.GRAY),
    
    // Subscription ranks
    VIP(10, RankCategory.SUBSCRIPTION, NamedTextColor.GREEN),
    VIP_PLUS(11, RankCategory.SUBSCRIPTION, NamedTextColor.GREEN),
    MVP(20, RankCategory.SUBSCRIPTION, NamedTextColor.AQUA),
    MVP_PLUS(21, RankCategory.SUBSCRIPTION, NamedTextColor.AQUA),
    
    // Staff ranks
    HELPER(50, RankCategory.STAFF, NamedTextColor.DARK_AQUA),
    MODERATOR(60, RankCategory.STAFF, NamedTextColor.DARK_GREEN),
    ADMIN(70, RankCategory.STAFF, NamedTextColor.RED),
    OWNER(100, RankCategory.STAFF, NamedTextColor.DARK_RED),
    
    // Special ranks
    YOUTUBE(30, RankCategory.SPECIAL, NamedTextColor.RED),
    TWITCH(31, RankCategory.SPECIAL, NamedTextColor.DARK_PURPLE),
    PARTNER(32, RankCategory.SPECIAL, NamedTextColor.GOLD);
}
```

## Usage

```java
RankService rankService = ServiceLocator.get(RankService.class);

// Get player's effective rank
CompletableFuture<Rank> rank = rankService.getEffectiveRank(playerId);

// Set rank
rankService.setRank(playerId, Rank.VIP);

// Set rank with expiration
rankService.setRank(playerId, Rank.VIP, Instant.now().plus(30, ChronoUnit.DAYS));

// Add additional rank
rankService.addRank(playerId, Rank.YOUTUBE);

// Remove rank
rankService.removeRank(playerId, Rank.YOUTUBE);

// Check if player has rank
CompletableFuture<Boolean> hasRank = rankService.hasRank(playerId, Rank.VIP);

// Get all ranks
CompletableFuture<Set<Rank>> allRanks = rankService.getRanks(playerId);
```

## Priority Resolution

When a player has multiple ranks, the effective rank is determined by priority (highest wins):

```java
// Player has VIP (priority 10) and MODERATOR (priority 60)
// Effective rank: MODERATOR

rankService.getEffectiveRank(playerId).thenAccept(rank -> {
    // rank = Rank.MODERATOR
});
```

## Rank Utilities

```java
// Check if player is admin
if (RankUtils.isAdmin(player)) {
    // Has ADMIN or OWNER rank
}

// Check if player is staff
if (RankUtils.isStaff(player)) {
    // Has any staff rank (HELPER, MODERATOR, ADMIN, OWNER)
}
```

## Categories

- `PLAYER` - Default player ranks
- `SUBSCRIPTION` - Paid subscription ranks
- `STAFF` - Staff member ranks
- `SPECIAL` - Special/promotional ranks

## Implementation Details

- Uses DataAPI for persistence
- In-memory caching for performance
- Async operations with CompletableFuture
- Automatic expiration checking