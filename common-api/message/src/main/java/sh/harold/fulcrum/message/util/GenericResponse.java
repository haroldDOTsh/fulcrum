package sh.harold.fulcrum.message.util;

public enum GenericResponse {
    ERROR("generic.error"),
    ERROR_GENERAL("generic.error.general"),
    ERROR_NO_PERMISSION("generic.error.nopermission"),
    ERROR_COOLDOWN("generic.error.cooldown");

    private final String key;

    GenericResponse(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
