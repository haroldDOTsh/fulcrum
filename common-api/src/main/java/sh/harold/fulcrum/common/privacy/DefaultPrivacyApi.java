package sh.harold.fulcrum.common.privacy;

import java.util.Objects;

public final class DefaultPrivacyApi implements PrivacyApi {
    private final PrivacyGate gate;
    private final PrivacyDomainRegistry registry;

    public DefaultPrivacyApi(PrivacyGate gate, PrivacyDomainRegistry registry) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public PrivacyGate gate() {
        return gate;
    }

    @Override
    public PrivacyDomainRegistry domains() {
        return registry;
    }
}
