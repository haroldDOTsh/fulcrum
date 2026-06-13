# Data Authority

PostgreSQL-backed durable data authority for Fulcrum.

This module is no longer a generic document database abstraction. Runtime code should not choose arbitrary collections, storage backends, or transaction wrappers. It submits domain commands through `DataAuthority.CommandPort` and reads supported projections through typed read ports.

## Storage Contract

- PostgreSQL is the canonical durable store for player profiles, sessions, rank projection/audit, match records, and match participant stats.
- Redis remains outside this module and should be used for ephemeral routing, leases, pub/sub, heartbeats, and short TTL state.
- Local memory is only a process cache.
- Message bus events are notifications and invalidations, not durable storage.
- MongoDB and JSON file storage are not part of the runtime data path.

## Command Writes

```java
DataAuthority.CommandPort commands = services.get(DataAuthority.CommandPort.class);

commands.submit(new DataAuthority.CommandEnvelope(
    UUID.randomUUID(),
    DataAuthority.CommandType.GRANT_RANK,
    "player:" + playerId,
    Map.of(
        "playerId", playerId.toString(),
        "primaryRank", "VIP",
        "ranks", List.of("VIP")
    ),
    "rank-admin:" + actorId,
    UUID.randomUUID().toString(),
    Instant.now()
));
```

Commands are idempotent by key and are recorded in `authority_commands`.

## Projection Reads

```java
DataAuthority.PlayerRankReader ranks = services.get(DataAuthority.PlayerRankReader.class);

ranks.findRanks(playerId).thenAccept(snapshot -> {
    snapshot.ifPresent(rankSnapshot -> {
        List<String> assignedRanks = rankSnapshot.ranks();
    });
});
```

Consumers should add narrow domain repositories or read ports when they need new data. Do not reintroduce generic document access as a public API.
