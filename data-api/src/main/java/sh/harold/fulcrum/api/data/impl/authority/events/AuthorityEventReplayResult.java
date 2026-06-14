package sh.harold.fulcrum.api.data.impl.authority.events;

/**
 * Side-effect-free replay outcome for an authority event projection.
 */
public final class AuthorityEventReplayResult {
    private static final String DEFAULT_PROJECTION_VERSION = "unversioned";

    private final String projectionVersion;
    private final String outputFingerprint;

    private AuthorityEventReplayResult(String projectionVersion, String outputFingerprint) {
        this.projectionVersion = projectionVersion == null || projectionVersion.isBlank()
            ? DEFAULT_PROJECTION_VERSION
            : projectionVersion.trim();
        if (outputFingerprint == null || outputFingerprint.isBlank()) {
            throw new IllegalArgumentException("outputFingerprint is required for projection replay");
        }
        this.outputFingerprint = outputFingerprint.trim();
    }

    /**
     * Creates a successful dry-run replay result.
     *
     * @param projectionVersion stable projection implementation version
     * @param outputFingerprint deterministic digest of the replayed projection output
     * @return replay result
     */
    public static AuthorityEventReplayResult success(String projectionVersion, String outputFingerprint) {
        return new AuthorityEventReplayResult(projectionVersion, outputFingerprint);
    }

    /**
     * Returns the projection implementation version that produced the replayed output.
     *
     * @return projection version, or {@code unversioned}
     */
    public String projectionVersion() {
        return projectionVersion;
    }

    /**
     * Returns the deterministic digest of the replayed projection output.
     *
     * @return replayed output fingerprint
     */
    public String outputFingerprint() {
        return outputFingerprint;
    }
}
