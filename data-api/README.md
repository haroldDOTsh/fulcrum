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

long expectedRevision = currentRankSnapshot.revision();
long now = System.currentTimeMillis();

commands.submit(new DataAuthority.PlayerRankCommand(
    DataAuthority.CommandManifest.create(
        UUID.randomUUID(),
        DataAuthority.CommandType.GRANT_RANK,
        "rank-admin:" + actorId,
        "player:" + playerId,
        "rank-grant:" + playerId + ":" + now,
        now + 5000L,
        "",
        expectedRevision
    ),
    playerId,
    "VIP",
    List.of("VIP")
));
```

Commands are idempotent by key, versioned by `schemaVersion`, fenced by authority epoch, and recorded in `authority_commands`.
Use `DataAuthority.ANY_REVISION` only for blind single-writer commands such as login/session heartbeats.

## Projection Reads

```java
DataAuthority.PlayerRankReader ranks = services.get(DataAuthority.PlayerRankReader.class);

ranks.findRanks(playerId).thenAccept(snapshot -> {
    snapshot.ifPresent(rankSnapshot -> {
        List<String> assignedRanks = rankSnapshot.ranks();
    });
});
```

When absence and stale projection state must be distinguished, use a quoted read:

```java
ranks.quoteRanks(playerId, DataAuthority.ReadRequirement.atLeast(expectedRevision))
    .thenAccept(read -> {
        if (read.satisfied()) {
            List<String> assignedRanks = read.snapshot().orElseThrow().ranks();
        }
    });
```

Consumers should add narrow domain repositories or read ports when they need new data. Do not reintroduce generic document access as a public API.

## Live PostgreSQL Proofs

`PostgresDataAuthorityIntegrationTest` and the registry snapshot integration test are tagged `live-postgres`.
By default they use Testcontainers and skip when Docker is unavailable. They can also target an external PostgreSQL
database:

- `FULCRUM_TEST_POSTGRES_JDBC_URL` or `-Dfulcrum.test.postgres.jdbcUrl=...`
- `FULCRUM_TEST_POSTGRES_USERNAME` or `-Dfulcrum.test.postgres.username=...`
- `FULCRUM_TEST_POSTGRES_PASSWORD` or `-Dfulcrum.test.postgres.password=...`
- `FULCRUM_TEST_POSTGRES_ALLOW_MUTATION=true` or `-Dfulcrum.test.postgres.allowMutation=true`
- Registry snapshot proof only: `FULCRUM_TEST_POSTGRES_ALLOW_ROLE_DDL=true` or `-Dfulcrum.test.postgres.allowRoleDdl=true`

To make missing Docker or an external target fail instead of skip, add
`FULCRUM_TEST_POSTGRES_REQUIRE_LIVE=true` or `-Dfulcrum.test.postgres.requireLive=true`.
