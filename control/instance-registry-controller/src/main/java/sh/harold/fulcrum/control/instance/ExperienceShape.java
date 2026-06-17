package sh.harold.fulcrum.control.instance;

public enum ExperienceShape {
    SHARED_SHARD("shared-shard");

    private final String wireName;

    ExperienceShape(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
