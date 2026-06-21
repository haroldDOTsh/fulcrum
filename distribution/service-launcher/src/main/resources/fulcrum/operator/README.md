# Fulcrum Operator Package

This package contains the operator-facing `fulcrum` launcher, the single-machine Compose unit, and the production Helm chart.

The launcher runs the published Fulcrum image through Docker or a compatible container runtime. Set `FULCRUM_CLI_IMAGE` to override the image reference.
