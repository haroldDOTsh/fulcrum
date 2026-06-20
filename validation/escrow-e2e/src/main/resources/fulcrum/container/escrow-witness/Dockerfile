FROM eclipse-temurin:26-jre

WORKDIR /opt/fulcrum
COPY escrow-e2e-witness /opt/fulcrum/escrow-e2e-witness
RUN chmod +x /opt/fulcrum/escrow-e2e-witness/bin/escrow-e2e-witness \
    && mkdir -p /var/run/fulcrum/escrow-e2e

ENTRYPOINT ["/opt/fulcrum/escrow-e2e-witness/bin/escrow-e2e-witness"]
