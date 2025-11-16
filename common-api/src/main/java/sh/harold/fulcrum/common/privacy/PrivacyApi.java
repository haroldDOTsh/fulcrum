package sh.harold.fulcrum.common.privacy;

/**
 * Aggregates privacy primitives (gate + registry) behind a single API.
 */
public interface PrivacyApi {

    PrivacyGate gate();

    PrivacyDomainRegistry domains();
}
