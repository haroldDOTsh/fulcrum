package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.capability.bundle.LoadedContribution;

import java.io.IOException;
import java.util.Objects;

public record PaperLoadedContribution<T>(
        LoadedContribution<T> loadedContribution,
        PaperContributionLoadReceipt receipt) implements AutoCloseable {
    public PaperLoadedContribution {
        loadedContribution = Objects.requireNonNull(loadedContribution, "loadedContribution");
        receipt = Objects.requireNonNull(receipt, "receipt");
    }

    public T provider() {
        return loadedContribution.provider();
    }

    @Override
    public void close() throws IOException {
        loadedContribution.close();
    }
}
