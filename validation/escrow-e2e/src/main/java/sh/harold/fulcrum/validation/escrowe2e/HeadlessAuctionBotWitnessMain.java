package sh.harold.fulcrum.validation.escrowe2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class HeadlessAuctionBotWitnessMain {
    private HeadlessAuctionBotWitnessMain() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> environment = System.getenv();
        HeadlessAuctionBotWitness.Options options = HeadlessAuctionBotWitness.Options.from(environment);
        HeadlessAuctionBotWitness.SettlementCertificate certificate = switch (options.executionMode()) {
            case "jvm-semantic-fixture" -> new HeadlessAuctionBotWitness().run(options);
            case "live-store" -> new LiveStoreHeadlessAuctionBotWitness().run(options, environment);
            default -> throw new IllegalArgumentException("unsupported escrow witness mode: " + options.executionMode());
        };
        String json = certificate.toJson();
        System.out.println(json);

        String proofFile = System.getenv("FULCRUM_WITNESS_PROOF_FILE");
        if (proofFile != null && !proofFile.isBlank()) {
            Path target = Path.of(proofFile);
            Files.createDirectories(target.getParent());
            Files.writeString(target, json + System.lineSeparator(), StandardCharsets.UTF_8);
            System.out.println("escrow-e2e-witness proofFile=" + target.toAbsolutePath());
        }
    }
}
