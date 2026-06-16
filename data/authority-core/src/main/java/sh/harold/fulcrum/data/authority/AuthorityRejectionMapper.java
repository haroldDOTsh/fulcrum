package sh.harold.fulcrum.data.authority;

@FunctionalInterface
public interface AuthorityRejectionMapper<R> {
    R map(AuthorityRejectionReason reason);
}
