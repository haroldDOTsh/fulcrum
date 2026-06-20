The `auctionEscrowImageContext` Gradle task assembles this image context with the installed
auction escrow backend application under `/opt/fulcrum/auction-escrow`.

Build the image with:

```text
.\gradlew.bat :validation:auction-escrow-backend:auctionEscrowImage
```

Override the tag with `-Pfulcrum.auctionEscrowImage=<image-ref>`.

For a prepared k3d/kind cluster, deploy the backend with:

```text
.\gradlew.bat :validation:auction-escrow-backend:auctionEscrowClusterDeploy
```

The courier tasks build the local image, import it into the configured `fulcrum.clusterProvider`
and `fulcrum.clusterName`, apply the rendered manifest with the configured kubeconfig/context,
then wait only for pod initialization. Full readiness intentionally remains blocked until live
registration and replay evidence are available.

The entrypoint validates the backend-authority identity, authority-domain grant inputs, and store
binding configuration before it can become authority-ready. In `serve` mode, after admitted
registration, it opens the Kafka/PostgreSQL/Cassandra/Valkey store clients, applies the escrow-owned
schema resources, publishes a boot command to `FULCRUM_ESCROW_COMMAND_TOPIC`, and writes readiness
evidence to `FULCRUM_ESCROW_READY_FILE` only after the guarded store-backed worker applies that
command. The ready document includes receipt lineage, store-binding fingerprint, replay watermark,
boot nonce, and the applied log offset for this boot.
