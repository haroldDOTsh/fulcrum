# Fulcrum Paper GameServer Image Context

This context is assembled by:

```text
.\gradlew.bat :distribution:service-launcher:paperGameserverImageContext
```

Build the image with:

```text
.\gradlew.bat :distribution:service-launcher:paperGameserverImage
```

Override the tag with `-Pfulcrum.paperGameserverImage=<image-ref>`.

The Dockerfile downloads the pinned stable Paper build declared in
`paper-server.lock` and verifies its SHA-256 digest during the image build. Update the lock only
from PaperMC's downloads service metadata and keep the download User-Agent non-generic.

The GameServer container runs the Fulcrum launcher and the Paper server in the same container. Configure `FULCRUM_PAPER_OBSERVATION_BRIDGE_URL` to a localhost URL such as `http://127.0.0.1:18080/observations` so the Paper plugin can hand join and quit observations to the launcher-side Kafka producer. Configure `FULCRUM_PAPER_CAPABILITY_BRIDGE_URL` to a localhost URL such as `http://127.0.0.1:18083/capabilities` so the Paper plugin can resolve profile, rank, and chat decoration through the launcher-side capability bridge. Configure `FULCRUM_PAPER_REWARD_BRIDGE_URL` to a localhost URL such as `http://127.0.0.1:18084/rewards` so the Paper plugin can report attached Subjects to the launcher-side standard-capability reward publisher. `FULCRUM_PAPER_REWARD_DELIVERY_COPIES` may be set above `1` by the cluster gate to inject duplicate reward command delivery while preserving the same idempotency keys. The launcher writes `FULCRUM_PAPER_ALLOCATION_FILE` after Agones reports the GameServer `Allocated`; the plugin reads that local file so join observations and lobby proof payloads use the allocated Session rather than the Fleet template Session.

The entrypoint writes `server.properties` and `bukkit.yml` before Paper starts. The default backend settings are `online-mode=false`, `server-port=25565`, `enforce-secure-profile=false`, `prevent-proxy-connections=false`, and `connection-throttle=-1`, matching the current Velocity image configuration of offline mode with no player-info forwarding while allowing deterministic same-host offline bot probes through the shared lobby Session.
