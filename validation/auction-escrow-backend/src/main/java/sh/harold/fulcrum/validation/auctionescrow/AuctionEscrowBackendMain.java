package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.HttpAuthorityBackendRegistrationClient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class AuctionEscrowBackendMain {
    private AuctionEscrowBackendMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        AuctionEscrowBackendConfig config = AuctionEscrowBackendConfig.fromEnvironment();
        Instant now = Instant.now();
        AuthorityBackendRegistrationRequest request = config.registrationRequest(now);
        System.out.println("auction-escrow-backend boot=" + config.bootSummary());
        System.out.println("auction-escrow-backend registrationRequest descriptorDigest="
                + request.descriptorDigest()
                + "|bundleDigest=" + request.bundleDigest()
                + "|sdkVersion=" + request.sdkVersion());
        Optional<AuthorityBackendRegistrationReceipt> maybeReceipt = Optional.empty();
        if (config.registrationEndpoint().isPresent()) {
            AuthorityBackendRegistrationReceipt receipt = new HttpAuthorityBackendRegistrationClient(
                    config.registrationEndpoint().orElseThrow())
                    .register(request);
            maybeReceipt = Optional.of(receipt);
            System.out.println("auction-escrow-backend registrationReceipt status="
                    + receipt.status()
                    + "|receiptId=" + receipt.receiptId()
                    + "|fencingEpoch=" + receipt.fencingEpoch()
                    + "|rejectionReason=" + receipt.rejectionReason().map(Enum::name).orElse("none"));
        } else {
            System.out.println("auction-escrow-backend registrationReceipt skipped|reason=no-registration-endpoint");
        }
        if (maybeReceipt.isPresent()
                && maybeReceipt.orElseThrow().admitted()
                && config.startupMode() == AuctionEscrowBackendConfig.StartupMode.BOOTSTRAP_CHECK) {
            AuctionEscrowReadinessEvidence evidence = AuctionEscrowBootReadiness.prove(
                    config,
                    maybeReceipt.orElseThrow(),
                    Instant.now(),
                    UUID.randomUUID().toString());
            try {
                AuctionEscrowReadinessPublisher.publish(Path.of(config.readyFile()), evidence);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("failed to publish escrow readiness evidence", exception);
            }
            System.out.println("auction-escrow-backend ready=true"
                    + "|readyFile=" + config.readyFile()
                    + "|receiptId=" + evidence.receiptId()
                    + "|appliedThrough=" + evidence.appliedThrough()
                    + "|evidenceDigest=" + evidence.evidenceDigest());
        } else if (maybeReceipt.isPresent()
                && maybeReceipt.orElseThrow().admitted()
                && config.startupMode() == AuctionEscrowBackendConfig.StartupMode.SERVE) {
            try (AuctionEscrowBackendRunner runner = AuctionEscrowBackendRunner.open(config, maybeReceipt.orElseThrow())) {
                AuctionEscrowReadinessEvidence evidence = runner.publishReadiness(
                        Path.of(config.readyFile()),
                        Instant.now(),
                        UUID.randomUUID().toString());
                System.out.println("auction-escrow-backend ready=true"
                        + "|readyFile=" + config.readyFile()
                        + "|receiptId=" + evidence.receiptId()
                        + "|appliedThrough=" + evidence.appliedThrough()
                        + "|evidenceDigest=" + evidence.evidenceDigest()
                        + "|runtime=store-backed");
                runner.serveForever();
            }
        } else {
            System.out.println("auction-escrow-backend ready=false|reason=awaiting-live-registration-and-replay"
                    + "|readyFile=" + config.readyFile());
        }
        if (config.startupMode() == AuctionEscrowBackendConfig.StartupMode.BOOTSTRAP_CHECK) {
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(30_000L);
        }
    }
}
