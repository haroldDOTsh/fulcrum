package sh.harold.fulcrum.data.subject;

public record SubjectExternalIdentity(String value) {
    public SubjectExternalIdentity {
        value = SubjectNames.requireNonBlank(value, "subjectExternalIdentity");
    }
}
