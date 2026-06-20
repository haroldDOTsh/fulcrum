FROM eclipse-temurin:26-jre

WORKDIR /opt/fulcrum/auction-escrow

COPY auction-escrow-backend /opt/fulcrum/auction-escrow

RUN chmod +x /opt/fulcrum/auction-escrow/bin/auction-escrow-backend \
    && mkdir -p /var/run/fulcrum

ENTRYPOINT ["/opt/fulcrum/auction-escrow/bin/auction-escrow-backend"]
