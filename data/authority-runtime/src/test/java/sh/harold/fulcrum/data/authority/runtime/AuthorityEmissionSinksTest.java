package sh.harold.fulcrum.data.authority.runtime;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AuthorityEmissionSinksTest {
    @Test
    void compositePublishesToEachSinkInOrder() {
        List<String> calls = new ArrayList<>();
        AuthorityEmission emission = new AuthorityEmission(AuthorityEmissionKind.EVENT, "aggregate", "payload");

        AuthorityEmissionSinks.composite(
                        value -> calls.add("first:" + value.key()),
                        value -> calls.add("second:" + value.key()))
                .publish(emission);

        assertEquals(List.of("first:aggregate", "second:aggregate"), calls);
    }
}
