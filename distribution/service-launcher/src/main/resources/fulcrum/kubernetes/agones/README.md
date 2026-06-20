# Fulcrum Lobby Paper Agones Resources

The `lobby-paper-fleet.yaml` resource is the Phase 2 Paper GameServer deployment
surface for the local Kubernetes cluster (k3d by default) plus Agones. It
declares the lobby namespace, content provisioner Job, Paper Fleet, and
FleetAutoscaler.
The `lobby-shared-shard-allocation.yaml` resource is applied only after the Fleet
has a Ready replica. It publishes a typed
`ctrl.cmd.shared-shard-allocation` command and waits for controller-service to
materialize `ctrl.state.shared-shard-allocation` endpoint state for Velocity.
The `lobby-paper-allocation.yaml` resource is the smoke allocation used to prove
Agones can move one Ready lobby Paper GameServer to `Allocated`; it is not the
main Phase 2 allocation path.
The `lobby-namespace.yaml` and `agones-helm-values.yaml` resources let the Gradle
deploy path install Agones before applying the lobby Fleet. Both generated and
existing-cluster gates use host Helm with the packaged values.
The `../substrate/lobby-kafka.yaml` resource provides the in-cluster
`fulcrum-kafka:9092` command-log endpoint, `fulcrum-postgres:5432` authority
record store, `fulcrum-cassandra:9042` hot-projection store,
`fulcrum-valkey:6379` cache endpoint, `fulcrum-object-store:9000` S3-compatible
object storage endpoint, schema provisioner Job that applies the packaged
PostgreSQL and Cassandra migration resources, `fulcrum-authority-service`
Deployment, `fulcrum-controller-service` for control topics, route commands,
host commands, and shared-shard allocation state, plus `fulcrum-worker-agent`
for `worker.jobs` and `worker.results` background work, without giving host
containers canonical store write credentials. The local generated cluster gate
sets the Agones allocator `DISABLE_MTLS=true` and `DISABLE_TLS=true` through the
packaged Helm values, then has controller-service call the allocator through its
in-cluster Service over HTTP. This keeps allocation live through Agones while
avoiding the chart-generated allocator server certificate shape that Java 26
rejects before custom CA-pinned trust can run.
The lobby Fleet now boots with only substrate placement, route, presence, and
session proof requirements. Domain-specific bootstrapping is intentionally
outside the core launcher path.

Build the images and apply the resources with:

```text
.\gradlew.bat :distribution:service-launcher:paperAgonesPhase2Deploy
```

The root cluster gate builds on this slice, creates a generated k3d cluster by
default or a kind cluster when `-Pfulcrum.clusterProvider=kind` is set, writes
the generated kubeconfig under `build/cluster-e2e`, imports the locally built
service-launcher, Paper GameServer, and Velocity proxy images into that cluster,
drives the Phase 3 Paper Agones plus Velocity L4 deploy path, runs the lobby
cluster verifier against the public Minecraft endpoint, and tears the generated
cluster down by default. Use `-Pfulcrum.keepCluster=true` to keep it after the
run for debugging:

```text
.\gradlew.bat clusterE2e
```

The generated-cluster compatibility task can also be run directly:

```text
.\gradlew.bat clusterK3sE2e
```

The existing-cluster path is exposed separately for persistent k3d, kind, or
other pre-provisioned Kubernetes contexts:

```text
.\gradlew.bat clusterExistingE2e
```

Use `-Pfulcrum.kubeContext=<context>` when the target cluster is not the current
`kubectl` context. Use `-Pfulcrum.kubeconfig=<path>` when the target cluster is
represented by a generated kubeconfig, such as the generated k3d/kind
`clusterE2e` profile; this takes precedence over `-Pfulcrum.kubeContext` for
`kubectl`, Helm, and the cluster verifier. The task builds and applies:

```text
ghcr.io/sh-harold/fulcrum-service-launcher:dev
ghcr.io/sh-harold/fulcrum-paper-gameserver:dev
ghcr.io/sh-harold/fulcrum-velocity-proxy:dev
```

Override those image tags with:

```text
-Pfulcrum.serviceLauncherImage=<image-ref>
-Pfulcrum.paperGameserverImage=<image-ref>
-Pfulcrum.velocityProxyImage=<image-ref>
-Pfulcrum.kafkaImage=<image-ref>
-Pfulcrum.postgresImage=<image-ref>
-Pfulcrum.cassandraImage=<image-ref>
-Pfulcrum.valkeyImage=<image-ref>
-Pfulcrum.objectStoreImage=<image-ref>
```

Generated-cluster lifecycle overrides:

```text
-Pfulcrum.clusterProvider=k3d
-Pfulcrum.k3dImage=rancher/k3s:v1.34.7-k3s1
-Pfulcrum.clusterName=fulcrum-cluster-e2e
-Pfulcrum.clusterApiPort=16443
-Pfulcrum.clusterMinecraftPort=25565
-Pfulcrum.clusterCreateTimeout=600s
-Pfulcrum.keepCluster=true
```

The default k3d image is centrally pinned to the newest tested K3s line that
starts on this Docker host's cgroup v1 profile. On hosts with cgroup v2, set
`-Pfulcrum.k3dImage=` to let k3d use its own default image, or pass an explicit
newer `rancher/k3s:<version>` image.

`-Pfulcrum.clusterApiPort=16443` is only needed when the generated cluster must
use a fixed Kubernetes API host port. Without it, Gradle reserves an available
localhost API port and the generated kubeconfig records that address.

`-Pfulcrum.clusterMinecraftPort=25565` is only needed when the generated cluster
must bind the conventional Minecraft host port. Without it, Gradle reserves an
available localhost port, exposes Velocity through the k3d/kind load balancer,
and records the selected port in
`distribution/service-launcher/build/cluster-e2e/minecraft-port.txt` for the
verifier.

`clusterK3sStart` writes the generated kubeconfig to
`distribution/service-launcher/build/cluster-e2e/kubeconfig.yaml`.
`clusterK3sImportImages` stages the locally built Fulcrum images into the
generated cluster with `k3d image import` or `kind load docker-image` before any
Fulcrum workload is applied. `clusterK3sStop` removes the generated cluster
after the gate unless `-Pfulcrum.keepCluster=true` is set. If a generated cluster
already exists with the configured name, `clusterK3sStart` deletes and recreates
it so changed lifecycle flags are not ignored.

`paperAgonesRenderManifests` renders `lobby-paper-fleet.yaml` and
`lobby-shared-shard-allocation.yaml` under `build/` with the effective image tags
before `kubectl apply`, and `paperAgonesRenderSubstrateManifests` does the same
for the substrate manifest, so the applied Jobs, authority-service Deployment,
worker-agent Deployment, and Fleet use the same images that the Gradle build just
produced. The substrate render path also tags the controller-service Deployment
with that same service-launcher image and applies the configured Kafka,
PostgreSQL, Cassandra, Valkey, and S3-compatible object-store images.

Then it verifies `kubectl`, Docker daemon access, and host Helm, applies
`lobby-namespace.yaml`, runs `paperAgonesInstallAgones` with the `agones`
chart from `https://agones.dev/chart/stable`, using
`agones-helm-values.yaml` and the centrally pinned Agones chart version through
`helm upgrade --install`. The deploy path then runs
`paperAgonesConfigureAllocatorTls` to wait for the Agones controller webhook and
allocator rollouts, with `DISABLE_MTLS=true` and `DISABLE_TLS=true` supplied by
`agones-helm-values.yaml`, then uses `paperAgonesSyncAllocatorCa` to copy Agones
allocator CA material into `fulcrum-lobby` for strict-TLS parity checks. The
local controller manifest uses the chart-supported plaintext allocator listener
instead of mounting that CA. It runs `paperAgonesApplySubstrate` for
`../substrate/lobby-kafka.yaml`, waits for
`paperAgonesWaitForKafka`, `paperAgonesWaitForValkey`,
`paperAgonesWaitForObjectStorage`, `paperAgonesWaitForPostgres`,
`paperAgonesWaitForCassandra`, `paperAgonesWaitForAuthoritySchema`,
`paperAgonesWaitForAuthorityService`, `paperAgonesWaitForControllerService`,
and `paperAgonesWaitForWorkerAgent`.
After the explicit Agones readiness gates pass, it verifies the Agones CRDs, runs `kubectl apply` for
`lobby-paper-fleet.yaml`, waits for the world-artifact Job, waits for the
one Ready Fleet replica, runs `paperAgonesApplySharedShardAllocation`
for `lobby-shared-shard-allocation.yaml`, waits for
`paperAgonesWaitForSharedShardAllocation`, waits for
`paperAgonesWaitForSharedShardAllocationState`, then runs
`paperAgonesRestartControllerServiceForReplay` to restart controller-service
and wait for it to become available again before Velocity login routing starts.
That makes the cluster gate exercise shared-shard allocation replay from
`ctrl.state.shared-shard-allocation` instead of relying only on fresh
controller-service memory. The task then prints the `fulcrum-lobby`
Deployment, Job, Pod, Fleet, FleetAutoscaler, GameServer, and
GameServerAllocation status.

Each Paper GameServer marks itself Ready, waits for Agones to move it to
`Allocated`, then writes `FULCRUM_PAPER_ALLOCATION_FILE` under the shared
`FULCRUM_PAPER_SERVER_ROOT`. The Paper plugin reads that local file before
publishing join observations and `fulcrum:lobby_probe` payloads, so scale-out
replicas prove their Velocity-correlated Route, allocated Session, Slot,
ResolvedManifest, and Paper session trace instead of the static Fleet template
assignment. The Paper Fleet passes `route-velocity-login-` as the route-id prefix
so host attach observations can acknowledge the RouteAttempt opened by Velocity
login routing.
The managed lobby Fleet sets `MINECRAFT_EULA=true` and serves the Fulcrum
runtime probe on port `18081`, leaving Agones' sidecar health server on port
`8080`.
Paper host pods receive artifact-read/object-store and hot-cache endpoints, but
the Paper Fleet manifest must not expose PostgreSQL or Cassandra canonical store
credentials.

`paperAgonesClusterPreflight` fails before deployment if the configured
Kubernetes context or generated kubeconfig is missing, the Kubernetes API is
unreachable, host Helm is not on `PATH`, or the current user cannot access the
Docker engine used to build the service-launcher, Paper GameServer, and Velocity
proxy images.

`paperAgonesAllocateLobby` remains available as a standalone smoke check for
`lobby-paper-allocation.yaml`. It fails unless Agones reports `Allocated` with a
non-empty `status.gameServerName`, but it intentionally bypasses controller
state and should not be used by the main Phase 2 deploy path.

Overrides:

```text
-Pfulcrum.agonesChartVersion=1.58.0
-Pfulcrum.agonesReleaseName=agones
-Pfulcrum.agonesSystemNamespace=agones-system
```
