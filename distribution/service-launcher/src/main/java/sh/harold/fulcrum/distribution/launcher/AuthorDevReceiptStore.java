package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class AuthorDevReceiptStore {
    static final String FILE_NAME = "author-dev-receipts.jsonl";

    private final Path stateDir;

    AuthorDevReceiptStore(Path stateDir) {
        this.stateDir = java.util.Objects.requireNonNull(stateDir, "stateDir");
    }

    Path append(AuthorDevReceipt receipt) {
        try {
            Files.createDirectories(stateDir);
            Path file = file();
            Files.writeString(
                    file,
                    receipt.toJson() + System.lineSeparator(),
                    Files.exists(file)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write author dev receipt", exception);
        }
    }

    Optional<AuthorDevReceipt> latest(String bundleId) {
        List<AuthorDevReceipt> receipts = read();
        for (int index = receipts.size() - 1; index >= 0; index--) {
            AuthorDevReceipt receipt = receipts.get(index);
            if (receipt.bundleId().equals(bundleId)) {
                return Optional.of(receipt);
            }
        }
        return Optional.empty();
    }

    private List<AuthorDevReceipt> read() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<AuthorDevReceipt> receipts = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    receipts.add(AuthorDevReceipt.fromJson(line));
                }
            }
            return receipts;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read author dev receipts", exception);
        }
    }

    Path file() {
        return stateDir.resolve(FILE_NAME);
    }
}
