# Fulcrum Operator Package

This package contains the operator-facing `fulcrum` launcher, the single-machine Compose unit, and the production Helm chart.

The launcher runs the published Fulcrum image through Docker or a compatible container runtime. Set `FULCRUM_CLI_IMAGE` to override the image reference.

`fulcrum up --tier full-engine`, `fulcrum status`, and `fulcrum down` supervise the Compose unit through the host Docker daemon. On Unix-like hosts the launcher mounts `/var/run/docker.sock` when present. Set `FULCRUM_DOCKER_SOCKET` for a different socket path or `DOCKER_HOST` for a remote Docker endpoint.

`fulcrum up --profile small-production` and `fulcrum up --profile large-production` wrap the packaged Helm chart. The launcher mounts `$HOME/.kube` when present; set `FULCRUM_KUBE_DIR` when the kubeconfig directory lives elsewhere.

`fulcrum cluster up|status|down` owns a disposable local k3d or kind cluster and installs the same Helm chart from published image references. The launcher image includes `kubectl`, `helm`, `k3d`, and `kind`; Docker access is still required for local cluster creation.

`fulcrum dev test --shape=in-memory|local-cluster` builds an author contribution, installs it through the declarative bundle reconcile path, and runs its smoke probe. The local-cluster shape boots or reuses the same `fulcrum cluster` state before the reconcile step.

`fulcrum author publish --project=<path> --to=oci://...` builds the scaffolded project when `--artifact` is omitted, preflights the bundle metadata, pushes it with ORAS, signs it with cosign, and prints the pinned OCI digest.
