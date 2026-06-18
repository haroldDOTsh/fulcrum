package sh.harold.fulcrum.host.velocity;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VelocityLoginSubjectRegistryTest {
    @Test
    void consumesAdmittedSubjectByUsernameRegardlessOfCase() {
        VelocityLoginSubjectRegistry registry = new VelocityLoginSubjectRegistry();
        SubjectId admitted = subject("11111111-1111-1111-1111-111111111111");
        SubjectId fallback = subject("22222222-2222-2222-2222-222222222222");

        registry.record("FulcrumBotOne", admitted);

        assertEquals("fulcrumbotone", registry.username(admitted).orElseThrow());
        assertEquals(admitted, registry.consume("fulcrumbotone", fallback));
        assertEquals("fulcrumbotone", registry.username(admitted).orElseThrow());
        assertEquals(fallback, registry.consume("FulcrumBotOne", fallback));
    }

    @Test
    void removeClearsPendingSubject() {
        VelocityLoginSubjectRegistry registry = new VelocityLoginSubjectRegistry();
        SubjectId admitted = subject("11111111-1111-1111-1111-111111111111");
        SubjectId fallback = subject("22222222-2222-2222-2222-222222222222");

        registry.record("FulcrumBotOne", admitted);
        registry.remove("FulcrumBotOne");

        assertEquals(fallback, registry.consume("FulcrumBotOne", fallback));
        assertEquals(java.util.Optional.empty(), registry.username(admitted));
    }

    @Test
    void rejectsBlankUsernames() {
        VelocityLoginSubjectRegistry registry = new VelocityLoginSubjectRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.record(" ", subject("11111111-1111-1111-1111-111111111111")));
        assertThrows(IllegalArgumentException.class, () -> registry.consume("", subject("22222222-2222-2222-2222-222222222222")));
    }

    private static SubjectId subject(String value) {
        return new SubjectId(UUID.fromString(value));
    }
}
