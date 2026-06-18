FROM eclipse-temurin:26-jre

ARG VELOCITY_SERVER_URL="https://fill-data.papermc.io/v1/objects/0ec616020166465dacca3b790d3db2b246f8f7c13b3aaacaae60c825744a66e0/velocity-3.5.0-SNAPSHOT-605.jar"
ARG VELOCITY_SERVER_SHA256="0ec616020166465dacca3b790d3db2b246f8f7c13b3aaacaae60c825744a66e0"
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
