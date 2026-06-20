The `escrowWitnessImageContext` Gradle task assembles this image context with the installed
validation escrow E2E witness application under `/opt/fulcrum/escrow-e2e-witness`.

Build the image with:

.\gradlew.bat :validation:escrow-e2e:escrowWitnessImage

Override the tag with `-Pfulcrum.escrowWitnessImage=<image-ref>`.

The application defaults to the semantic headless bot certificate when no mode is provided. The
Kubernetes Job manifest runs `FULCRUM_WITNESS_MODE=live-store`, which drives the same auction
experience script through Kafka and store probes, deletes the escrow pod after durable-close
evidence, waits for a replacement Ready pod UID, and then sends the replay probe.
