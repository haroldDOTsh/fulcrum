package sh.harold.fulcrum.sdk.authority;

@FunctionalInterface
public interface AuthorityBackendRegistrationClient {
    AuthorityBackendRegistrationReceipt register(AuthorityBackendRegistrationRequest request);
}
