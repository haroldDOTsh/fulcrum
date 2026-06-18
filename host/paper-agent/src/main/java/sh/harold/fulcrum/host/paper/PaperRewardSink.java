package sh.harold.fulcrum.host.paper;

public interface PaperRewardSink {
    void publish(PaperSessionRewardReport report);
}
