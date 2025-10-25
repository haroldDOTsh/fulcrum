package sh.harold.fulcrum.registry.network;

import java.util.List;

public final class NetworkProfileValidationException extends RuntimeException {
    private final String profileId;
    private final List<String> errors;

    public NetworkProfileValidationException(String profileId, List<String> errors) {
        super("Network profile '%s' failed validation: %s".formatted(profileId, errors));
        this.profileId = profileId;
        this.errors = List.copyOf(errors);
    }

    public String getProfileId() {
        return profileId;
    }

    public List<String> getErrors() {
        return errors;
    }
}
