# Rank API

Unified rank system with priority-based resolution and expiration support.

## Rank Structure

```java
public enum Rank {
    // Base player rank
    DEFAULT(0, RankCategory.PLAYER, NamedTextColor.GRAY),

    // Donator ranks (permanent)
    DONATOR_1(10, RankCategory.PLAYER, NamedTextColor.GREEN),
    DONATOR_2(20, RankCategory.PLAYER, NamedTextColor.AQUA),
    DONATOR_3(30, RankCategory.PLAYER, NamedTextColor.LIGHT_PURPLE),

    // Subscription rank
    DONATOR_4(40, RankCategory.SUBSCRIPTION, NamedTextColor.GOLD),

    // Staff ranks
    HELPER(100, RankCategory.STAFF, NamedTextColor.BLUE),
    STAFF(200, RankCategory.STAFF, NamedTextColor.DARK_RED);
}
```

## Usage

```java
RankService rankService = ServiceLocator.get(RankService.class);

// Get player's effective rank
CompletableFuture<Rank> rank = rankService.getEffectiveRank(playerId);

// Set rank
rankService.setRank(playerId, Rank.DONATOR_1);

// Set rank with expiration
rankService.setRank(playerId, Rank.DONATOR_4, Instant.now().plus(30, ChronoUnit.DAYS));

// Add additional rank
rankService.addRank(playerId, Rank.DONATOR_4);

// Remove rank
rankService.removeRank(playerId, Rank.YOUTUBE);

// Check if player has rank
CompletableFuture<Boolean> hasRank = rankService.hasRank(playerId, Rank.DONATOR_2);

// Get all ranks
CompletableFuture<Set<Rank>> allRanks = rankService.getRanks(playerId);
```

## Priority Resolution

When a player has multiple ranks, the effective rank is determined by priority (highest wins):

```java
// Player has DONATOR_2 (priority 20) and STAFF (priority 200)
// Effective rank: STAFF

rankService.getEffectiveRank(playerId).thenAccept(rank -> {
    // rank = Rank.STAFF
});
```

## Rank Utilities

```java
// Check if player is admin
if (RankUtils.isAdmin(player)) {
    // Has STAFF rank
}

// Check if player is staff
if (RankUtils.isStaff(player)) {
    // Has any staff rank (HELPER or STAFF)
}
```

## Categories

- `PLAYER` - Default player ranks (DEFAULT, DONATOR_1/2/3)
- `SUBSCRIPTION` - Paid subscription ranks (DONATOR_4)
- `STAFF` - Staff member ranks (HELPER, STAFF)

## Implementation Details

- Uses DataAPI for persistence
- In-memory caching for performance
- Async operations with CompletableFuture
- Automatic expiration checking
- Base rank state is embedded in the shared `players` document under `rank` and
  `rankInfo.*`; this keeps the proxy and backend in sync without an extra
  collection.
- Every rank mutation emits an audit entry (when backed by PostgreSQL) into the
  `player_rank_audit` table with executor, timestamps, and the resulting rank
  snapshot.
