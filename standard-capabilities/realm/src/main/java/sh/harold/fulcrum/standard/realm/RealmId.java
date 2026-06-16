package sh.harold.fulcrum.standard.realm;

public record RealmId(String value) {
    public RealmId {
        value = RealmNames.requireNonBlank(value, "realmId");
    }
}
