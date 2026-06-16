package sh.harold.fulcrum.data.authority.runtime;

@FunctionalInterface
public interface AuthorityOffsetCommitter {
    void commit(AuthorityOffset offset);
}
