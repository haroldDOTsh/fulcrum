package sh.harold.fulcrum.data.contract;

public enum FieldType {
    STRING("String", "TEXT"),
    INSTANT("Instant", "TIMESTAMPTZ"),
    LONG("long", "BIGINT");

    private final String javaType;
    private final String migrationType;

    FieldType(String javaType, String migrationType) {
        this.javaType = javaType;
        this.migrationType = migrationType;
    }

    public String javaType() {
        return javaType;
    }

    public String migrationType() {
        return migrationType;
    }
}
