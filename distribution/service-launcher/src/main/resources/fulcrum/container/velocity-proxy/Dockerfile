FROM eclipse-temurin:26-jre

ARG VELOCITY_SERVER_URL="https://api.papermc.io/v2/projects/velocity/versions/3.4.0-SNAPSHOT/builds/559/downloads/velocity-3.4.0-SNAPSHOT-559.jar"
ARG VELOCITY_SERVER_SHA256="ccc49f71751ece26568d3476392d6130c8b43f2e5f3a88313325b9278a52babd"
ARG VELOCITY_DOWNLOAD_USER_AGENT="fulcrum-velocity-proxy/1.0 (https://github.com/sh-harold/fulcrum)"

WORKDIR /opt/fulcrum/velocity

COPY fulcrum /opt/fulcrum/fulcrum
COPY plugins/FulcrumVelocityAgent.jar /opt/fulcrum/velocity/plugins/FulcrumVelocityAgent.jar
COPY velocity.toml /opt/fulcrum/velocity/velocity.toml
COPY entrypoint.sh /opt/fulcrum/bin/velocity-proxy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && curl -fsSL \
        -H "User-Agent: ${VELOCITY_DOWNLOAD_USER_AGENT}" \
        -o /opt/fulcrum/velocity/velocity-server.jar \
        "${VELOCITY_SERVER_URL}" \
    && echo "${VELOCITY_SERVER_SHA256}  /opt/fulcrum/velocity/velocity-server.jar" | sha256sum -c - \
    && chmod +x /opt/fulcrum/bin/velocity-proxy \
    && apt-get purge -y --auto-remove curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 25565 8080

ENTRYPOINT ["/opt/fulcrum/bin/velocity-proxy"]
