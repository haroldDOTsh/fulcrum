package sh.harold.fulcrum.sdk.authority;

@FunctionalInterface
public interface AuthorityBackendRegistrationClient {
    AuthorityBackendRegistrationReceipt register(AuthorityBackendRegistrationRequest request);

    default AuthorityBackendDeregistrationReceipt deregister(AuthorityBackendDeregistrationRequest request) {
        return AuthorityBackendDeregistrationReceipt.unsupported(request);
    }
}
