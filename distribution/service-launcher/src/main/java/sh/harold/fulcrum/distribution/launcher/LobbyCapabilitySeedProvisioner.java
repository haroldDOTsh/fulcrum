package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class LobbyCapabilitySeedProvisioner {
    private LobbyCapabilitySeedProvisioner() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("LobbyCapabilitySeedProvisioner reads configuration from environment");
        }
        System.out.println("publishedCapabilitySeedSubjects=0");
        System.out.println("publishedCapabilitySeedCommands=0");
    }

    static SubjectId offlineModeSubjectId(String username) {
        String checked = requireNonBlank(username, "username");
        return new SubjectId(UUID.nameUUIDFromBytes(("OfflinePlayer:" + checked).getBytes(StandardCharsets.UTF_8)));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
