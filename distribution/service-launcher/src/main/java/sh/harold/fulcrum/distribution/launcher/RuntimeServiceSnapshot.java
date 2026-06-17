package sh.harold.fulcrum.distribution.launcher;

import java.time.Instant;

record RuntimeServiceSnapshot(
        String role,
        String processFamily,
        String instanceId,
        String instanceKind,
        String principalId,
        String credentialRef,
        boolean live,
        boolean ready,
        long loopCount,
        Instant startedAt) {
}
