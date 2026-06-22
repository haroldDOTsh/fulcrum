package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BundleInstanceStateStore {
    static final String FILE_NAME = "bundle-instances.jsonl";

    private final Path stateDir;

    BundleInstanceStateStore(Path stateDir) {
        this.stateDir = java.util.Objects.requireNonNull(stateDir, "stateDir");
    }

    Path append(BundleInstanceRecord record) {
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
            throw new UncheckedIOException("Could not write bundle instance state", exception);
        }
    }

    List<BundleInstanceRecord> read() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<BundleInstanceRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    records.add(BundleInstanceRecord.fromJson(line));
                }
            }
            return records;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read bundle instance state", exception);
        }
    }

    Map<String, BundleInstanceRecord> latestByBundle() {
        Map<String, BundleInstanceRecord> latest = new LinkedHashMap<>();
        read().forEach(record -> latest.put(record.bundleId(), record));
        return latest;
    }

    Path file() {
        return stateDir.resolve(FILE_NAME);
    }
}
