# Fulcrum Velocity L4 Resources

The `lobby-velocity.yaml` resource is the Phase 3 Velocity and L4 deployment
surface. It keeps the public Minecraft TCP endpoint on a Kubernetes
`LoadBalancer` Service named `fulcrum-velocity-l4` and leaves Paper GameServers
private behind RouteController-issued transfers.

Build and apply the Phase 3 slice with:

```text
.\gradlew.bat :distribution:service-launcher:paperAgonesPhase3Deploy
```

The root cluster gate now creates a generated k3d cluster by default, or a kind
cluster when `-Pfulcrum.clusterProvider=kind` is set, imports the locally built
Fulcrum images into that cluster, and targets this Phase 3 slice:

```text
.\gradlew.bat clusterE2e
```

The generated-cluster compatibility task is also exposed directly:

```text
.\gradlew.bat clusterK3sE2e
```

The existing-cluster profile remains available for a pre-provisioned cluster:

```text
.\gradlew.bat clusterExistingE2e
```

`clusterE2e` runs `velocityL4WaitForReady` and then `lobbyClusterE2eVerify`
after the Phase 3 deploy path. The verifier resolves the `fulcrum-velocity-l4`
Service, waits for the public TCP endpoint to answer a headless Minecraft status
handshake, derives the protocol number from that status response, and then
verifies the configured lobby bot reaches the Paper play state by waiting for
the `fulcrum:lobby_probe` plugin-message proof. That proof is emitted by the
Paper agent after it places the Subject on the bedrock lobby spawn and resolves
profile, rank, and chat decoration through the Paper capability bridge. The
proof also carries the Velocity route id, allocated Slot id, ResolvedManifest
id, and Paper session trace id, and the verifier rejects proofs whose route id
does not match the deterministic Velocity login route or whose manifest or trace
do not match the configured expectations. On Kubernetes-resolved runs, the
verifier also reads `ctrl.state.route-attempt` from the in-cluster Kafka Pod and
requires each accepted login proof to match ACKED controller RouteAttempt state.
It reads `cmd.route` and requires traced `open-route` and `acknowledge-route`
commands for every accepted proof, including the same Subject, Route, Session,
Paper Instance, trace id, fresh route expiry, and acknowledgement time, while
rejecting any fresh command for the seeded denied Subject.
It also reads `state.route` and requires every accepted proof to have
ACKNOWLEDGED Route authority state for the same Subject, Route, Session, Paper
Instance, fresh route expiry, and acknowledgement time.
It reads `ctrl.cmd.queue-roster`, `cmd.presence`,
`ctrl.cmd.shared-shard-placement`, `ctrl.cmd.route-attempt`, and
`ctrl.cmd.lifecycle-trace` and requires the Velocity login bridge to have
emitted typed queue submit/form, Presence claim, shared-shard placement,
route-attempt request, proxy-issue, host-prepare, and lifecycle trace commands
for every accepted proof, with the
expected cluster principal, fencing, revisions, deterministic ids, trace origin,
Subject, Presence, Route, Session, Slot, Paper Instance, ResolvedManifest, and
allocation candidate correlation. The seeded denied Subject must be absent from
those login-routing command logs.
It also reads `ctrl.state.queue-roster` and requires each accepted login's
queue intent to be rostered with a formed one-Subject roster while rejecting any
queue or roster state for the seeded denied Subject.
It reads `ctrl.state.lifecycle-trace` and requires each accepted login's trace
timeline to include queue, roster, allocation, route-attempt, Paper host attach,
and active Session milestones with matching Session and ResolvedManifest
correlation.
It reads `host.velocity.routes` and `host.paper.commands` and requires the
addressed `proxy.route` and `host.route.prepare` commands to carry the same
route attempt, Subject, Route, Session, Paper Instance, ResolvedManifest, and
trace correlation expected by the accepted lobby proof while rejecting any downstream host-route command for the seeded denied Subject.
It reads `ctrl.cmd.shared-shard-allocation` and requires every proof Session to
have a typed shared-shard allocation request for the configured Experience,
Pool, ResolvedManifest, and expected lobby allocation trace origin.
It reads `ctrl.state.shared-shard-allocation` and requires every proof Session
to match controller-owned shared-shard allocation state for the configured
Experience, Pool, Slot, Paper Instance, and ResolvedManifest.
It reads `state.presence` and requires every accepted login Subject to have
LIVE Presence authority state with the deterministic Velocity login Presence
id, matching Session and Route, and a fresh lease.
It reads `state.standard.player-profile`, `state.standard.rank`, and
`state.standard.punishment` and requires accepted proof Subjects to match
materialized standard capability state for display name and rank, while the
seeded denied Subject must have active punishment state matching the login-gate
reason.
It also reads `cmd.standard.player-profile`, `cmd.standard.rank`, and
`cmd.standard.punishment` and requires the same Subjects to have typed
capability seed commands with the expected principal, fencing, revision, trace,
and payload values before accepting the materialized state proof.
It also reads `state.session` from the same Kafka Pod and requires every unique
Session observed in accepted lobby proofs to have ACTIVE Session authority state
with a fresh lease and the matching Slot, Paper Instance, and ResolvedManifest.
It also reads `cmd.session` and requires traced `open-session` and
`activate-session` commands for each proof Session, including the same trace id,
fresh lease, Slot, Paper Instance, and ResolvedManifest correlation.
The verifier then logs in a second accepted bot and asserts both proofs came
from the same Paper Instance, Session, and Slot, proving the shared-shard lobby
path before checking the Velocity login gate denies the seeded punished bot. The
Gradle-rendered default keeps the lobby hard capacity low for this E2E path:
the first two accepted bots fill one shared lobby, a third login is denied to
trigger controller-owned allocation, and a fourth bot must then reach a
different Paper Instance, Session, and Slot. When the verifier resolves the endpoint
from Kubernetes Service status, it also checks the Agones Fleet reports the
expected allocated replica count for the shared lobby and that each proof's
Paper Instance is an `Allocated` Agones GameServer in that Fleet with matching
Pool, Session, Slot, ResolvedManifest, and trace metadata.
When route-attempt state verification is enabled, the same state read also
checks that the seeded denied Subject is absent from controller route-attempt
state after the punishment login gate rejects it. Route authority command-log
verification rejects any fresh `cmd.route` command for that denied Subject,
Route authority state verification rejects any fresh Route state for that
denied Subject, and Presence state verification also rejects any fresh LIVE Presence for that denied Subject. It also reads
`host.observation` and requires a raw `host.session-attached` observation for
each accepted proof, matching Subject, Route, Session, Paper Instance, Pool,
trace id, and freshness while rejecting any fresh host session attachment for
the denied Subject. Projection consistency verification fails the cluster gate
unless Kafka state, Cassandra hot projections, PostgreSQL authority records,
and Valkey cache evidence are all present for the same proof-derived lobby
expectations. Trace correlation verification fails the cluster gate unless
traced command logs, host observations, controller and authority state, and
Agones GameServer metadata are all present with positive matches. Use these
overrides when the endpoint is not exposed through Service status yet or when
changing the verifier identities and expected seeded capability values:

```text
-Pfulcrum.lobbyEndpointHost=<host-or-ip>
-Pfulcrum.lobbyEndpointPort=25565
-Pfulcrum.lobbyNamespace=fulcrum-lobby
-Pfulcrum.lobbyVelocityService=fulcrum-velocity-l4
-Pfulcrum.kubeconfig=<generated-kubeconfig-path>
-Pfulcrum.lobbyNodeHost=127.0.0.1
-Pfulcrum.lobbyAgonesFleetName=fulcrum-lobby-paper
-Pfulcrum.verifyLobbyAgonesFleetState=true
-Pfulcrum.expectedLobbyAgonesAllocatedReplicas=2
-Pfulcrum.verifyLobbyRouteAttemptState=true
-Pfulcrum.lobbyRouteAttemptStateTopic=ctrl.state.route-attempt
-Pfulcrum.verifyLobbyLoginRoutingCommandLog=true
-Pfulcrum.lobbyQueueRosterCommandTopic=ctrl.cmd.queue-roster
-Pfulcrum.verifyLobbyQueueRosterState=true
-Pfulcrum.lobbyQueueRosterStateTopic=ctrl.state.queue-roster
-Pfulcrum.lobbyPresenceAuthorityCommandTopic=cmd.presence
-Pfulcrum.lobbySharedShardPlacementCommandTopic=ctrl.cmd.shared-shard-placement
-Pfulcrum.lobbyRouteAttemptCommandTopic=ctrl.cmd.route-attempt
-Pfulcrum.lobbyLifecycleTraceCommandTopic=ctrl.cmd.lifecycle-trace
-Pfulcrum.verifyLobbyLifecycleTraceState=true
-Pfulcrum.lobbyLifecycleTraceStateTopic=ctrl.state.lifecycle-trace
-Pfulcrum.verifyLobbyRouteAuthorityCommandLog=true
-Pfulcrum.lobbyRouteAuthorityCommandTopic=cmd.route
-Pfulcrum.verifyLobbyRouteAuthorityState=true
-Pfulcrum.lobbyRouteAuthorityStateTopic=state.route
-Pfulcrum.verifyLobbyHostRouteCommandLogs=true
-Pfulcrum.lobbyProxyRouteCommandTopic=host.velocity.routes
-Pfulcrum.lobbyPaperHostCommandTopic=host.paper.commands
-Pfulcrum.verifyLobbyHostObservationLog=true
-Pfulcrum.lobbyHostObservationTopic=host.observation
-Pfulcrum.verifyLobbyPresenceAuthorityState=true
-Pfulcrum.lobbyPresenceAuthorityStateTopic=state.presence
-Pfulcrum.verifyLobbyStandardCapabilityState=true
-Pfulcrum.lobbyPlayerProfileStateTopic=state.standard.player-profile
-Pfulcrum.lobbyRankStateTopic=state.standard.rank
-Pfulcrum.lobbyPunishmentStateTopic=state.standard.punishment
-Pfulcrum.verifyLobbyStandardCapabilityCommandLog=true
-Pfulcrum.lobbyPlayerProfileCommandTopic=cmd.standard.player-profile
-Pfulcrum.lobbyRankCommandTopic=cmd.standard.rank
-Pfulcrum.lobbyPunishmentCommandTopic=cmd.standard.punishment
-Pfulcrum.verifyLobbySessionAuthorityState=true
-Pfulcrum.lobbySessionAuthorityStateTopic=state.session
-Pfulcrum.verifyLobbySessionAuthorityCommandLog=true
-Pfulcrum.lobbySessionAuthorityCommandTopic=cmd.session
-Pfulcrum.verifyLobbySharedShardAllocationCommandLog=true
-Pfulcrum.lobbySharedShardAllocationCommandTopic=ctrl.cmd.shared-shard-allocation
-Pfulcrum.verifyLobbySharedShardAllocationState=true
-Pfulcrum.lobbySharedShardAllocationStateTopic=ctrl.state.shared-shard-allocation
-Pfulcrum.verifyLobbyProjectionConsistency=true
-Pfulcrum.verifyLobbyTraceCorrelation=true
-Pfulcrum.lobbyKafkaPodName=fulcrum-kafka-0
-Pfulcrum.lobbyKafkaContainerName=kafka
-Pfulcrum.lobbyKafkaBootstrapServer=localhost:9092
-Pfulcrum.lobbyKafkaConsoleConsumerPath=/opt/kafka/bin/kafka-console-consumer.sh
-Pfulcrum.verifyLobbyScaleOut=true
-Pfulcrum.lobbyTargetCapacity=1
-Pfulcrum.lobbyHardCapacity=2
-Pfulcrum.minecraftProtocolVersion=775
-Pfulcrum.lobbyLoginUsername=FulcrumBotOne
-Pfulcrum.secondLobbyLoginUsername=FulcrumBotTwo
-Pfulcrum.scaleOutTriggerLobbyLoginUsername=FulcrumBotThree
-Pfulcrum.scaleOutTriggerDeniedLobbyLoginReasonContains=No lobby route is currently available
-Pfulcrum.scaleOutLobbyLoginUsername=FulcrumBotFour
-Pfulcrum.expectedLobbyResolvedManifestId=manifest-lobby-bedrock-v1
-Pfulcrum.expectedLobbyExperienceId=experience-lobby
-Pfulcrum.expectedLobbyPoolId=pool-lobby
-Pfulcrum.expectedLobbyTraceId=trace-paper-session-lobby-shared
-Pfulcrum.expectedLobbySpawnBlock=bedrock
-Pfulcrum.expectedLobbySpawnWorld=world
-Pfulcrum.expectedLobbyBedrockBlockX=0
-Pfulcrum.expectedLobbyBedrockBlockY=64
-Pfulcrum.expectedLobbyBedrockBlockZ=0
-Pfulcrum.expectedLobbyPlayerX=0.5
-Pfulcrum.expectedLobbyPlayerY=65.0
-Pfulcrum.expectedLobbyPlayerZ=0.5
-Pfulcrum.expectedLobbyPlayerYaw=0.0
-Pfulcrum.expectedLobbyPlayerPitch=0.0
-Pfulcrum.expectedLobbyDisplayName=Fulcrum Bot One
-Pfulcrum.expectedLobbyRankLabel=Admin
-Pfulcrum.expectedLobbyDecoratedChatContains=[Admin] Fulcrum Bot One: fulcrum-proof-chat
-Pfulcrum.expectedSecondLobbyDisplayName=Fulcrum Bot Two
-Pfulcrum.expectedSecondLobbyRankLabel=Admin
-Pfulcrum.expectedSecondLobbyDecoratedChatContains=[Admin] Fulcrum Bot Two: fulcrum-proof-chat
-Pfulcrum.expectedScaleOutLobbyDisplayName=Fulcrum Bot Four
-Pfulcrum.expectedScaleOutLobbyRankLabel=Admin
-Pfulcrum.expectedScaleOutLobbyDecoratedChatContains=[Admin] Fulcrum Bot Four: fulcrum-proof-chat
-Pfulcrum.lobbyScaleOutTimeout=PT60S
-Pfulcrum.deniedLobbyLoginUsername=FulcrumBannedOne
-Pfulcrum.deniedLobbyLoginReasonContains=Banned from the lobby
-Pfulcrum.lobbyEndpointReadyTimeout=PT120S
-Pfulcrum.lobbyRouteAttemptStateTimeout=PT60S
-Pfulcrum.lobbyRouteAttemptStateFreshnessSkew=PT5S
-Pfulcrum.lobbyPresenceAuthorityStateTimeout=PT60S
-Pfulcrum.lobbyPresenceAuthorityStateFreshnessSkew=PT5S
-Pfulcrum.lobbyStandardCapabilityStateTimeout=PT60S
-Pfulcrum.lobbySessionAuthorityStateTimeout=PT60S
-Pfulcrum.lobbySessionAuthorityStateFreshnessSkew=PT5S
-Pfulcrum.lobbySharedShardAllocationStateTimeout=PT60S
-Pfulcrum.lobbyVerifierTimeout=PT90S
```

When both `-Pfulcrum.kubeconfig` and `-Pfulcrum.kubeContext` are present, the
generated kubeconfig is used for the verifier's `kubectl` reads and
port-forwards. That is the path used by the generated k3d/kind cluster profile.

For generated `clusterE2e` runs, `-Pfulcrum.lobbyEndpointPort` normally does
not need to be set. The generated k3d/kind cluster reserves an available
localhost Minecraft port, records it in
`distribution/service-launcher/build/cluster-e2e/minecraft-port.txt`, and the
verifier reads that value. Use `-Pfulcrum.clusterMinecraftPort=25565` only when
the generated cluster must bind the conventional Minecraft port.

The default Velocity image is:

```text
ghcr.io/sh-harold/fulcrum-velocity-proxy:dev
```

Override it with:

```text
-Pfulcrum.velocityProxyImage=<image-ref>
```

`velocityL4RenderManifests` writes the effective `lobby-velocity.yaml` under
`build/kubernetes/velocity/` before `velocityL4Apply` runs `kubectl apply`.

The launcher-side Velocity agent consumes proxy route commands from
`FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC`, resolves backend endpoints from
`FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC`, calls the local plugin bridge,
and emits route acknowledgements through `FULCRUM_ROUTE_COMMAND_TOPIC`. The
packaged lobby manifest defaults those to `host.velocity.routes`,
`ctrl.state.shared-shard-allocation`, and `cmd.route`. The cluster verifier also
reads `ctrl.cmd.shared-shard-allocation` so it proves the typed allocation
request before accepting the materialized endpoint state.

Allowed login decisions also publish typed substrate intents from the launcher
side. The Velocity plugin still only calls the localhost login bridge; the
launcher bridge claims Presence through `FULCRUM_PRESENCE_COMMAND_TOPIC`,
submits queue and roster intents through `FULCRUM_QUEUE_ROSTER_COMMAND_TOPIC`,
submits shared-shard placement through
`FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC`, and, when allocation state has a
matching Paper Session candidate, submits route-attempt request, proxy, and host
prepare commands through `FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC`, plus lifecycle
observations through `FULCRUM_LIFECYCLE_TRACE_COMMAND_TOPIC`. The packaged
lobby manifest defaults those to `cmd.presence`, `ctrl.cmd.queue-roster`,
`ctrl.cmd.shared-shard-placement`, `ctrl.cmd.route-attempt`, and
`ctrl.cmd.lifecycle-trace`.

The login routing bridge pins the lobby placement descriptor with
`FULCRUM_LOBBY_EXPERIENCE_ID`, `FULCRUM_LOBBY_POOL_ID`,
`FULCRUM_LOBBY_AGONES_FLEET_NAME`, `FULCRUM_LOBBY_TARGET_CAPACITY`,
`FULCRUM_LOBBY_HARD_CAPACITY`, `FULCRUM_LOBBY_RESOLVED_MANIFEST_ID`, and
`FULCRUM_LOBBY_CAPABILITY_SCOPE_FINGERPRINT`. These values must match the Paper
Fleet assignment in the Agones manifest. `velocityL4RenderManifests` rewrites
the target and hard capacity from `-Pfulcrum.lobbyTargetCapacity` and
`-Pfulcrum.lobbyHardCapacity`; the default cluster E2E values are `1` and `2`
so the verifier can prove scale-out without hundreds of bot logins.
`FULCRUM_VELOCITY_PRESENCE_LEASE` controls the claimed Presence lease duration
and defaults to `PT5M`.

`FULCRUM_VELOCITY_ROUTE_BRIDGE_URL` is the localhost HTTP bridge used by the
launcher-side route consumer to ask the Velocity plugin to execute the native
proxy transfer. The packaged lobby manifest defaults it to
`http://127.0.0.1:18081/routes`.

`FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL` is the localhost HTTP bridge used by
the Velocity plugin login hook to ask the launcher-side runtime for an
admission decision. The packaged lobby manifest defaults it to
`http://127.0.0.1:18082/login-gate`; the launcher reads the active punishment
cache through `FULCRUM_VALKEY_ENDPOINT`, which defaults to `fulcrum-valkey:6379`.
Velocity host pods must not receive PostgreSQL, Cassandra, or object-store
credentials; the deployment manifest keeps canonical store access behind the
authority/controller service family and gives Velocity only the command-log,
route/login bridge, placement-state, and hot-cache endpoints it needs.
