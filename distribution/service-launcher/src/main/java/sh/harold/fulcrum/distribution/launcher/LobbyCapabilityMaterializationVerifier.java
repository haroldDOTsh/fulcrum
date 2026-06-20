package sh.harold.fulcrum.distribution.launcher;

public final class LobbyCapabilityMaterializationVerifier {
    private LobbyCapabilityMaterializationVerifier() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("LobbyCapabilityMaterializationVerifier reads configuration from environment");
        }
        System.out.println("materializedCapabilitySubjects=0");
        System.out.println("materializedCapabilityChecks=0");
    }
}
