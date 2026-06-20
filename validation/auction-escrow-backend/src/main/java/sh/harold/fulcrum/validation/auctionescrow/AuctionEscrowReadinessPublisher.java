package sh.harold.fulcrum.validation.auctionescrow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

final class AuctionEscrowReadinessPublisher {
    private AuctionEscrowReadinessPublisher() {
    }

    static void publish(Path readyFile, AuctionEscrowReadinessEvidence evidence) throws IOException {
        Path checkedReadyFile = Objects.requireNonNull(readyFile, "readyFile");
        AuctionEscrowReadinessEvidence checkedEvidence = Objects.requireNonNull(evidence, "evidence");
        Path parent = checkedReadyFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = checkedReadyFile.resolveSibling(checkedReadyFile.getFileName() + ".tmp");
        Files.writeString(tempFile, checkedEvidence.document(), StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, checkedReadyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, checkedReadyFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

