# Fulcrum Velocity Proxy Image Context

This context is assembled by:

```text
.\gradlew.bat :distribution:service-launcher:velocityProxyImageContext
```

Build the image with:

```text
.\gradlew.bat :distribution:service-launcher:velocityProxyImage
```

Override the tag with `-Pfulcrum.velocityProxyImage=<image-ref>`.

The Dockerfile downloads the pinned Velocity proxy build declared in
`velocity-server.lock` and verifies its SHA-256 digest during the image build.
The proxy container runs the Fulcrum launcher and Velocity in the same container
so the Velocity plugin and launcher-side route command handling share the same
Instance environment.

`velocity.toml` includes a localhost `lobby` placeholder server so Velocity has
a valid server registry at startup. The fallback list is intentionally empty:
Fulcrum routes live logins through the launcher-side route bridge and the
dynamically registered Paper backend endpoint.
The packaged E2E config also sets `[advanced].login-ratelimit = 0` so rapid
headless proof clients exercise Fulcrum routing and capacity denial instead of
Velocity's IP attempt limiter.
