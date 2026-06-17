package sh.harold.fulcrum.host.paper;

public interface PaperSessionLifecyclePort {
    void openSession(PaperSessionOpenRequest request);

    void activateSession(PaperSessionActivationRequest request);
}
