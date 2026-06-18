package sh.harold.fulcrum.host.velocity;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VelocityInitialRouteCoordinatorTest {
    private static final SubjectId SUBJECT_ID =
            new SubjectId(UUID.fromString("33333333-3333-3333-3333-333333333333"));

    @Test
    void waiterReceivesOfferedInitialBackendAndAcknowledgesRoute() throws Exception {
        try (VelocityInitialRouteCoordinator coordinator = new VelocityInitialRouteCoordinator(Duration.ofSeconds(5))) {
            CompletionStage<Optional<VelocityInitialRouteSelection>> awaited = coordinator.await(SUBJECT_ID);
            CompletionStage<Boolean> offered = coordinator.offer(SUBJECT_ID, "fulcrum-instance-paper-target-1");

            Optional<VelocityInitialRouteSelection> selection = awaited.toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);

            assertTrue(selection.isPresent());
            assertEquals("fulcrum-instance-paper-target-1", selection.orElseThrow().backendName());
            assertFalse(offered.toCompletableFuture().isDone());

            selection.orElseThrow().acknowledge(true);

            assertTrue(offered.toCompletableFuture().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void routeCanArriveBeforeInitialServerEvent() throws Exception {
        try (VelocityInitialRouteCoordinator coordinator = new VelocityInitialRouteCoordinator(Duration.ofSeconds(5))) {
            CompletionStage<Boolean> offered = coordinator.offer(SUBJECT_ID, "fulcrum-instance-paper-target-1");

            Optional<VelocityInitialRouteSelection> selection = coordinator.await(SUBJECT_ID)
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);

            assertTrue(selection.isPresent());
            assertEquals("fulcrum-instance-paper-target-1", selection.orElseThrow().backendName());

            selection.orElseThrow().acknowledge(false);

            assertFalse(offered.toCompletableFuture().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void waiterTimesOutWhenNoRouteArrives() throws Exception {
        try (VelocityInitialRouteCoordinator coordinator = new VelocityInitialRouteCoordinator(Duration.ofMillis(10))) {
            Optional<VelocityInitialRouteSelection> selection = coordinator.await(SUBJECT_ID)
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);

            assertTrue(selection.isEmpty());
        }
    }
}
