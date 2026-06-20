package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.capability.bundle.LoadedContribution;

import java.io.IOException;
import java.util.Objects;

public record VelocityLoadedContribution<T>(
        LoadedContribution<T> loadedContribution,
        VelocityContributionLoadReceipt receipt) implements AutoCloseable {
    public VelocityLoadedContribution {
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
