package sh.harold.fulcrum.api.contract;

public record Revision(long value) {
    public Revision {
        if (value < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
