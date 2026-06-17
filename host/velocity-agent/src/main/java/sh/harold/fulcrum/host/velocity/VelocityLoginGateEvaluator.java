package sh.harold.fulcrum.host.velocity;

@FunctionalInterface
public interface VelocityLoginGateEvaluator {
    VelocityLoginGateDecision evaluate(VelocityLoginGateRequest request);
}
