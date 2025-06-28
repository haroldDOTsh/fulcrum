package sh.harold.fulcrum.api.message;

public enum GenericResponse {
    ERROR("generic.error");

    private final String key;

    GenericResponse(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}