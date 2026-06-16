package sh.harold.fulcrum.standard.realm;

public record RealmSnapshotId(String value) {
    public RealmSnapshotId {
        value = RealmNames.requireNonBlank(value, "realmSnapshotId");
    }
}
