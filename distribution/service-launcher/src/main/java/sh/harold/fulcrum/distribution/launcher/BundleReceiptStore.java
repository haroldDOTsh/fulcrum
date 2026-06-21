package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BundleReceiptStore {
    static final String FILE_NAME = "bundle-receipts.jsonl";

    private final Path stateDir;

    BundleReceiptStore(Path stateDir) {
        this.stateDir = java.util.Objects.requireNonNull(stateDir, "stateDir");
    }

    Path append(List<BundleReconcileReceipt> receipts) {
        if (receipts.isEmpty()) {
            return file();
        }
        StringBuilder payload = new StringBuilder();
        receipts.forEach(receipt -> payload.append(receipt.toJson()).append(System.lineSeparator()));
        try {
            Files.createDirectories(stateDir);
            Path file = file();
            Files.writeString(
                    file,
                    payload.toString(),
                    Files.exists(file)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write bundle reconcile receipts", exception);
        }
    }

    List<BundleReconcileReceipt> read() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<BundleReconcileReceipt> receipts = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    receipts.add(BundleReconcileReceipt.fromJson(line));
                }
            }
            return receipts;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read bundle reconcile receipts", exception);
        }
    }

    Map<String, BundleReconcileReceipt> latestByBundle() {
        Map<String, BundleReconcileReceipt> latest = new LinkedHashMap<>();
        read().forEach(receipt -> latest.put(receipt.bundleId(), receipt));
        return latest;
    }

    Path file() {
        return stateDir.resolve(FILE_NAME);
    }
}
