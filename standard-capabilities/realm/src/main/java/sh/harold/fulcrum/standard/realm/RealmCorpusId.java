package sh.harold.fulcrum.standard.realm;

public record RealmCorpusId(String value) {
    public RealmCorpusId {
        value = RealmNames.requireNonBlank(value, "realmCorpusId");
    }
}
