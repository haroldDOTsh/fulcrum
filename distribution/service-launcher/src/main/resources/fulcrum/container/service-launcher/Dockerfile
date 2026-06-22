FROM eclipse-temurin:26-jre

LABEL org.opencontainers.image.source="https://github.com/haroldDOTsh/fulcrum"

ARG HELM_VERSION=v3.18.6
ARG KUBECTL_VERSION=v1.34.1
ARG K3D_VERSION=v5.8.3
ARG KIND_VERSION=v0.30.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl docker.io docker-compose-v2 \
    && curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" -o /tmp/helm.tar.gz \
    && tar -xzf /tmp/helm.tar.gz -C /tmp \
    && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
    && curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" -o /usr/local/bin/kubectl \
    && curl -fsSL "https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/k3d-linux-amd64" -o /usr/local/bin/k3d \
    && curl -fsSL "https://kind.sigs.k8s.io/dl/${KIND_VERSION}/kind-linux-amd64" -o /usr/local/bin/kind \
    && chmod +x /usr/local/bin/helm /usr/local/bin/kubectl /usr/local/bin/k3d /usr/local/bin/kind \
    && rm -rf /var/lib/apt/lists/* /tmp/helm.tar.gz /tmp/linux-amd64

WORKDIR /opt/fulcrum/service

COPY fulcrum /opt/fulcrum/fulcrum

RUN chmod +x /opt/fulcrum/fulcrum/bin/fulcrum

ENTRYPOINT ["/opt/fulcrum/fulcrum/bin/fulcrum"]
