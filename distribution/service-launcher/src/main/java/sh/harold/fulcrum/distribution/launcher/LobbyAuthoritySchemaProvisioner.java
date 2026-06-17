package sh.harold.fulcrum.distribution.launcher;

public final class LobbyAuthoritySchemaProvisioner {
    private static final String DISABLED_REASON =
            "Authority schemas must be delivered by generated migrations, not service-launcher runtime provisioning.";

    private LobbyAuthoritySchemaProvisioner() {
    }

    public static void main(String[] args) {
        throw new UnsupportedOperationException(DISABLED_REASON);
    }

    static String disabledReason() {
        return DISABLED_REASON;
    }
}
