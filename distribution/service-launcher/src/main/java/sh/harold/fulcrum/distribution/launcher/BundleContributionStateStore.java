package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class BundleContributionStateStore {
    static final String FILE_NAME = "bundle-contributions.jsonl";

    private final Path stateDir;

    BundleContributionStateStore(Path stateDir) {
        this.stateDir = java.util.Objects.requireNonNull(stateDir, "stateDir");
    }

    Path append(BundleContributionRecord record) {
        try {
            Files.createDirectories(stateDir);
            Path file = file();
            Files.writeString(
                    file,
                    record.toJson() + System.lineSeparator(),
                    Files.exists(file)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write bundle contribution state", exception);
        }
    }

    List<BundleContributionRecord> read() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<BundleContributionRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    records.add(BundleContributionRecord.fromJson(line));
                }
            }
            return records;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read bundle contribution state", exception);
        }
    }

    Map<String, BundleContributionRecord> latestByBundle() {
        Map<String, BundleContributionRecord> latest = new LinkedHashMap<>();
        read().forEach(record -> latest.put(record.bundleId(), record));
        return latest;
    }

    Optional<BundleContributionRecord> latest(String bundleId) {
        List<BundleContributionRecord> records = read();
        for (int index = records.size() - 1; index >= 0; index--) {
            BundleContributionRecord record = records.get(index);
            if (record.bundleId().equals(bundleId)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    Path file() {
        return stateDir.resolve(FILE_NAME);
    }
}
