FROM eclipse-temurin:26-jre

ARG PAPER_SERVER_URL="https://fill-data.papermc.io/v1/objects/6c59eefe2752f97ee79f83ad0a61fe14865a0976e4ac06597f53de6c44afd6c5/paper-26.1.2-70.jar"
ARG PAPER_SERVER_SHA256="6c59eefe2752f97ee79f83ad0a61fe14865a0976e4ac06597f53de6c44afd6c5"
ARG PAPER_DOWNLOAD_USER_AGENT="fulcrum-paper-gameserver/1.0 (https://github.com/haroldDOTsh/fulcrum)"

WORKDIR /opt/fulcrum/paper

COPY fulcrum /opt/fulcrum/fulcrum
COPY plugins/FulcrumPaperAgent.jar /opt/fulcrum/paper/plugins/FulcrumPaperAgent.jar
COPY contribution-bundles /opt/fulcrum/paper/contribution-bundles
COPY entrypoint.sh /opt/fulcrum/bin/paper-gameserver

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && curl -fsSL \
        -H "User-Agent: ${PAPER_DOWNLOAD_USER_AGENT}" \
        -o /opt/fulcrum/paper/paper-server.jar \
        "${PAPER_SERVER_URL}" \
    && echo "${PAPER_SERVER_SHA256}  /opt/fulcrum/paper/paper-server.jar" | sha256sum -c - \
    && chmod +x /opt/fulcrum/bin/paper-gameserver \
    && apt-get purge -y --auto-remove curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 25565 18081

ENTRYPOINT ["/opt/fulcrum/bin/paper-gameserver"]
