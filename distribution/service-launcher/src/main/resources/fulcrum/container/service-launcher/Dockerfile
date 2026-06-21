FROM eclipse-temurin:26-jre

LABEL org.opencontainers.image.source="https://github.com/haroldDOTsh/fulcrum"

WORKDIR /opt/fulcrum/service

COPY fulcrum /opt/fulcrum/fulcrum

RUN chmod +x /opt/fulcrum/fulcrum/bin/fulcrum

ENTRYPOINT ["/opt/fulcrum/fulcrum/bin/fulcrum"]
