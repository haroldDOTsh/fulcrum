package sh.harold.fulcrum.data.authority.runtime;

import sh.harold.fulcrum.data.authority.AuthorityEmission;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AuthorityEmissionSinks {
    private AuthorityEmissionSinks() {
    }

    public static AuthorityEmissionSink composite(AuthorityEmissionSink first, AuthorityEmissionSink... rest) {
        Objects.requireNonNull(first, "first");
        List<AuthorityEmissionSink> sinks = Arrays.stream(rest == null ? new AuthorityEmissionSink[0] : rest)
                .map(sink -> Objects.requireNonNull(sink, "sink"))
                .toList();
        return emission -> {
            first.publish(emission);
            for (AuthorityEmissionSink sink : sinks) {
                sink.publish(emission);
            }
        };
    }
}
