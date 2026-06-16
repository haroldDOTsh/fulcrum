package sh.harold.fulcrum.api.kernel;

public record ExperienceId(String value) {
    public ExperienceId {
        value = Ids.requireNonBlank(value, "experienceId");
    }
}
