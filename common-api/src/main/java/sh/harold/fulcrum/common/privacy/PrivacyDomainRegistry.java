package sh.harold.fulcrum.common.privacy;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public final class PrivacyDomainRegistry {
    private final Map<PrivacyDomain, PrivacyDomainConfig> configs = new EnumMap<>(PrivacyDomain.class);

    public void register(PrivacyDomain domain, PrivacyDomainConfig config) {
        configs.put(domain, config);
    }

    public Optional<PrivacyDomainConfig> get(PrivacyDomain domain) {
        return Optional.ofNullable(configs.get(domain));
    }

    public PrivacyDomainConfig require(PrivacyDomain domain) {
        return configs.get(domain);
    }
}
