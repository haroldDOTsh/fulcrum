# Fulcrum

Fulcrum is a personal project that digs into system design for Minecraft networks. It's my answer to *"what's a public
plugin?"*
Fulcrum is heavily inspired by and tries to mimic Hypixel’s ecosystem as close as possible to 1:1 (just without the
Minecraft 1.7 tech
debt)- I tried to stay consistent to the source material whenever possible (referencing public admin statements
regarding their systems), making educated assumptions where there are uncertainties.

Why? I've been a huge fan of Hypixel systems for years, and it also serves as lab for experimental minigames/features
that can be built on top of Paper and Velocity.

### Info

- Minecraft Version: `1.21.10`
- Protocol Version: `773`
- Toolchain: Java 21, Gradle 9.2, Paper + Velocity APIs

## Quick Start

Fulcrum expects a registry, one or more proxies, and any number of backend runtimes. Configuration for Redis, MongoDB,
and PostgreSQL lives in `runtime/src/main/resources/database-config.yml`; copy that into your server directory and
adjust connection strings as needed.

```bash
# build every module + run unit tests
./gradlew clean build

# start the central registry (Redis backed)
./gradlew :registry-service:runRegistry

# launch a Velocity proxy wired to the registry
./gradlew :runtime-velocity:runVelocity

# boot a backend runtime with the Fulcrum plugin
./gradlew :runtime:runServer
```

> [!NOTE]
> Once a backend boots, the registry automatically registers it, announcing it to every proxy. Heartbeat metrics keep it
> alive. Scaling out horizontally is as simple as running another backend with the runtime jar.

## Integrating Fulcrum In Your Project

Whether you want typed access to the message bus, reuse the rank/session stack, or ship a module that Fulcrum can host,
wire your own Gradle build like this:

1. **Add JitPack.**
   ```kotlin
   // settings.gradle.kts
   dependencyResolutionManagement {
       repositories {
           mavenCentral()
           maven("https://jitpack.io")
       }
   }
   ```

2. **Pull the modules you need.**
   ```kotlin
   dependencies {
       implementation("com.github.haroldDOTsh.fulcrum:common-api:4.6.4") // Contracts, ranks, session/message APIs
       compileOnly("com.github.haroldDOTsh.fulcrum:runtime:4.6.4") // Paper runtime hooks (module development)
       compileOnly("com.github.haroldDOTsh.fulcrum:runtime-velocity:4.6.4") // Proxy hooks (if you extend the proxy stack)
   }
   ```
   Every published artifact keeps the `com.github.haroldDOTsh.fulcrum` group, so switching between tags or snapshots is
   just a version
   bump. JitPack will also expose `registry-service` if you need to use that somewhere.

3. **Register your module.** Too lengthy for this README, check the **Getting Started** wiki page for lifecycle
   integration, module
   registration, and environment configuration details.

Drop the `runtime` jar (and your own module jar) into Paper, the `runtime-velocity` jar into Velocity, and start
the registry service for the full ecosystem (visit the releases page if you don't want to compile them yourself).
Fulcrum exposes services like `RankService`, `SessionService`, and `MessageService` through
`FulcrumPlatform#getService`,
so lean on constructor injection or the provided lookups instead of re-resolving singletons yourself.
For a complete walkthrough of module registration and lifecycle hooks, see the **Getting Started** wiki page.

### Development

| Module             | Description                                                                                        |
|--------------------|----------------------------------------------------------------------------------------------------|
| `common`           | Shared contracts, storage adapters, messaging façade, rank primitives, lifecycle events.           |
| `runtime`          | Paper plugin: lifecycle container, rank system, menu and chat APIs, minigame engine, action flags. |
| `runtime-velocity` | Velocity integration: backend discovery, smart routing, party and staff tooling.                   |
| `registry-service` | Springless microservice that brokers registrations, slot orchestration, rank mutations.            |

- **Rank system:** inject `RankService` to read/write player ranks, or call helpers like `RankUtils.isStaff(sender)` and
  `RankService.addRank(uuid, Rank.DONATOR_4, context)`; mutations flow through the registry for consistency.
- **Fulcrum modules:** runtime features register through the lifecycle container - declare a module id, provide your
  `PluginFeature`, and the bootstrap sequence wires dependencies automatically based on `environment.yml`.
- **Minigame engine:** define state machines, register slot families, and Fulcrum handles provisioning, transitions, and
  spectator routing; your game logic plugs into clean callbacks instead of juggling raw Bukkit events.

## How Does This Work?

Fulcrum splits responsibilities so each process stays focused:

1. **Registry Service**: The authoritative source of truth. It watches Redis channels for `ServerRegistrationRequest`
   messages, allocates permanent IDs, tracks heartbeats, and pushes routing updates to proxies.
2. **Velocity Proxies**: Subscribe to registry broadcasts, register backend endpoints in real time, enforce
   ranks/parties/chat rules, and proxy players where the slot orchestrator tells them to go.
3. **Runtime Backends**: Paper servers running the Fulcrum runtime. They host minigames, expose module APIs, and
   replicate state through the message bus.

Everything speaks through the message bus: typed envelopes, channel constants, and Jackson-serialised payloads keep
cross service communication predictable. Environments (`ENVIRONMENT` file + `environment.yml`) decide which runtime
features to load, which modules to activate, and which role the server should register under - allowing the same
`/plugins` folder to boot as a lobby, a minigame shard, or a staff testing server. When the Paper host runs behind
NAT/Docker, add a second line to the `ENVIRONMENT` file with the public IP so registry lookups get the reachable address
instead of `server.properties`.

> [!NOTE]
> Fulcrum takes advantage of Paper’s bootstrap sequence: environment driven module lists translate to plugin ids that
> Paper enables automatically, so spinning up a new server type is mostly a matter of adding an environment profile.

## A New Way to Think About Data

A lot of plugins hammer the database on every click, introducing latency and contention. Fulcrum keeps player data hot
in memory while they are online, synchronises via the bus, and only persists to canonical storage when it matters.

- **Session Service:** maintains an in memory + Redis backed session object per player. Gameplay code reads/writes
  against that object, not directly against MongoDB/PostgreSQL.
- **Message Bus:** coordinates write behind persistence, rank changes, cosmetic unlocks, and other cross node events.
  Mutations publish to the bus, consumers react, and the registry propagates authoritative state.
- **Lock Free Optimisation:** because every backend works against its locally cached session and defers persistence, we
  avoid row level locks and network jitter that typical “write immediately” plugins trigger.
- **Rank Caching:** the runtime caches rank sets, invalidates on mutation responses from the registry, and refreshes
  sessions so servers agree instantly on who has staff powers.

### Tri-Storage Strategy (MongoDB + Redis + PostgreSQL)

Fulcrum embraces three datastores, each for what it does best:

| Store          | Role                                                                                                                                       |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **Redis**      | Ephemeral session cache, message bus transport, registry metadata. Keeps online player state low latency and allows cross service pub/sub. |
| **MongoDB**    | Long term player documents, minigame records, JSON-like structures that evolve with content updates.                                       |
| **PostgreSQL** | Structured assets: world templates, POI metadata, analytics snapshots; anything that benefits from relational guarantees and indexing.     |

Sessions hydrate from MongoDB on login, stay live in Redis + runtime memory, broadcast deltas through the message bus,
and flush back to MongoDB (and, where appropriate, PostgreSQL) on logout. The registry uses Redis for quick lookups and
uses Mongo/Postgres for state that should survive restarts. The architecture gives us durability without drowning the
game loop in IO.

## Many Games, One Server

Backend runtimes that host minigames can spin up multiple Fulcrum module based minigames at once - Bedwars, SkyWars,
seasonal events, whatever you can dream of, all sharing the same hardware. This is powered by the slot orchestration
system: the registry decides what capacity is needed, the runtime provisions slots for each game family, and the
minigame engine binds the right module to each slot.

- **Provisioning:** Slot definitions describe how many instances of each game you want. When the registry says “spin up
  another SkyWars,” the runtime loads the matching module and allocates an arena.
- **State Machines:** Games declare states (lobby, countdown, in progress, end) and transitions; the engine executes
  them atomically, updates action flags (build, PvP, spectator abilities), and raises events to your code.
- **Spectators & Routing:** Post elimination players get routed through the same system; they can be put into spectator
  slots or moved to another queue via the proxy routing pipeline.
- **Data Lifecycle:** Each match gets its own MongoDB collection segment (or document set) for stats/logs, created
  lazily and cleaned up when the match ends. The engine handles tidy teardown so slots are reusable.

The goal: game developers focus on game mechanics, not infrastructure cleanup.

### Slot Orchestrator

The slot orchestrator is the runtime side scheduler that keeps lobbies full and matches spinning. Provisioning follows a
simple pipeline:

1. Registry receives demand (player joins queue, admin command, autoscaling trigger) and publishes a provision request
   for a slot family.
2. Slot orchestrator picks up the request, checks existing capacity, and reserves a slot on the runtime.
3. Minigame engine instantiates or reuses the module backing that slot, loads a world template, registers POIs, and
   transitions the state machine into lobby.
4. Registry announces the new slot so proxies can route players, and the orchestrator keeps the lifecycle updated (
   filling, in game, ending, idle).

While the match runs, the orchestrator tracks slot state, pushes updates back to the registry, and flips action flags so
players only get the abilities their current phase allows. When the round ends, the slot is torn down, statistics are
flushed, and the capacity is marked idle for the next provision. Adding a new minigame is mostly declarative: define the
slot family, declare its module, and the orchestrator handles the rest.

### Arena & World Template Storage

World content stays versionable and sharable:

- `.schem` templates live in PostgreSQL alongside metadata (biome, player count, recommended kit, tags).
- On startup, the runtime pulls the templates down, caches the full schematic payloads locally, and tracks checksums so
  updates roll out safely.
- Minigame modules request a template id, receive the cached world copy, and can do so without rebooting servers -
  perfect for live map updates.

### Points of Interest (POIs)

Builders place POI markers directly on the map before it is exported. When the runtime loads an arena it scans for those
configured POI blocks or entities, registers them with the minigame context, and exposes them to your logic (spawn
points, generators, NPC stands). Update the template, redeploy, and every new match instantly uses the revised POI
layout.

### Minigame Pipeline Diagram

```
Player runs /play command
    -->
Velocity proxy records intent, sends request to registry
    -->
Registry looks for an available backend that advertises the requested slot family/variant
    -->
If none are free, registry issues a provision request to that family
    -->
Runtime slot orchestrator reserves a slot and asks the minigame engine to initialise it
    -->
Minigame engine claims a world template, pastes the schematic into a fresh world folder, and activates the state machine
    -->
Engine registers POIs, applies action flags, and enters lobby countdown
    -->
Registry broadcasts the new slot routing info back to proxies
    -->
Velocity transfers queued players into the freshly prepared arena
```

## What Else Lives Here?

There is a bunch of other standardized systems you would expect on a real network: menu and chat APIs, parties,
scoreboards, rank system, moderation tooling, and more. This README is just a quick summary of our primary systems; have
a peep at the GitHub wiki (coming soon) for deeper feature explanations.
