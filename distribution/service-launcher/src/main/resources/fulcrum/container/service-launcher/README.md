The `serviceLauncherImageContext` Gradle task assembles this image context with the
installed Fulcrum service-launcher distribution under `/opt/fulcrum/fulcrum`.

Build the image with:

```text
.\gradlew.bat :distribution:service-launcher:serviceLauncherImage
```

Override the tag with `-Pfulcrum.serviceLauncherImage=<image-ref>`.

The image can run the normal `fulcrum` launcher entrypoint or be overridden by a
Kubernetes Job to execute a specific provisioning main class such as
`sh.harold.fulcrum.distribution.launcher.LobbyWorldArtifactProvisioner`,
`sh.harold.fulcrum.distribution.launcher.LobbyAuthoritySchemaProvisioner`,
`sh.harold.fulcrum.distribution.launcher.LobbyCapabilitySeedProvisioner`, or
`sh.harold.fulcrum.distribution.launcher.LobbyCapabilityMaterializationVerifier`.
The authority schema provisioner applies the packaged PostgreSQL and Cassandra
migration resources for the lobby authority stores; normal service-launcher run
mode does not create schema at startup.
