package sh.harold.fulcrum.data.authority;

import sh.harold.fulcrum.api.contract.Revision;

import java.util.List;
import java.util.Objects;

public record AuthorityMutationResult<S, R>(
        Revision revision,
        S state,
        R response,
        List<AuthorityEmission> emissions) {
    public AuthorityMutationResult {
        revision = Objects.requireNonNull(revision, "revision");
        response = Objects.requireNonNull(response, "response");
        emissions = List.copyOf(Objects.requireNonNull(emissions, "emissions"));
    }
}
