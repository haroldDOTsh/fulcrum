package sh.harold.fulcrum.host.paper;

enum PaperSessionHostEventType {
    ATTACHED("paper.session-attached"),
    DETACHED("paper.session-detached");

    private final String eventType;

    PaperSessionHostEventType(String eventType) {
        this.eventType = eventType;
    }

    String eventType() {
        return eventType;
    }
}
