package sh.harold.fulcrum.data.store.postgresql;

public interface JdbcAuthorityStateCodec<S> {
    String encode(S state);

    S decode(String payload);
}
