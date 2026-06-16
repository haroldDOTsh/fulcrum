package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.data.authority.AuthorityEmission;

@FunctionalInterface
public interface AuthorityEmissionSink {
    void publish(AuthorityEmission emission);
}
