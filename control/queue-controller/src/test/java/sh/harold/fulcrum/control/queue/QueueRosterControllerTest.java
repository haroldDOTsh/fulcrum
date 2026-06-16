package sh.harold.fulcrum.control.queue;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QueueRosterControllerTest {
    private static final PrincipalId PRINCIPAL_ID = new PrincipalId("principal-controller-queue");
    private static final ExperienceId EXPERIENCE_ID = new ExperienceId("experience-arena");
    private static final PoolId POOL_ID = new PoolId("pool-paper-arena");
    private static final Optional<String> MODE_ID = Optional.of("standard");
    private static final QueuePartitionKey PARTITION_KEY = new QueuePartitionKey(EXPERIENCE_ID, MODE_ID, POOL_ID);
    private static final Instant BASE_TIME = Instant.parse("2026-06-16T12:00:00Z");
    private static final SubjectId SUBJECT_1 = subject("00000000-0000-0000-0000-000000000001");
    private static final SubjectId SUBJECT_2 = subject("00000000-0000-0000-0000-000000000002");
    private static final SubjectId SUBJECT_3 = subject("00000000-0000-0000-0000-000000000003");

    @Test
    void queueIntentsFormRosterAndReplayFromEvents() {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(7);
        List<QueueRosterEvent> events = new ArrayList<>();

        record = accept(controller, record, submit("queue-1", List.of(SUBJECT_1), 1, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-1", BASE_TIME, events).record();
        record = accept(controller, record, submit("queue-2", List.of(SUBJECT_2), 3, 1),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-2", BASE_TIME.plusSeconds(1), events).record();
        QueueRosterDecision rosterDecision = accept(controller, record, form("roster-1", List.of(queueId("queue-1"), queueId("queue-2")), 2, 2),
                ControlQueueNames.FORM_ROSTER_INTENT, "cmd-form", BASE_TIME.plusSeconds(2), events);
        record = rosterDecision.record();

        assertTrue(rosterDecision.emissions().stream().anyMatch(emission -> emission.kind() == QueueRosterControlEmissionKind.ROSTER_READY));
        RosterIntentSnapshot roster = record.state().rosterIntent(new RosterIntentId("roster-1")).orElseThrow();
        assertEquals(List.of(queueId("queue-2"), queueId("queue-1")), roster.queueIntentIds());
        assertEquals(List.of(SUBJECT_2, SUBJECT_1), roster.subjectIds());
        assertEquals(QueueIntentStatus.ROSTERED, record.state().queueIntent(queueId("queue-1")).orElseThrow().status());
        assertEquals(QueueIntentStatus.ROSTERED, record.state().queueIntent(queueId("queue-2")).orElseThrow().status());
        assertEquals(new Revision(3), record.revision());
        assertEquals(record, QueueRosterController.replay(7, events));
    }

    @Test
    void duplicateCommandReplaysStoredDecisionWithoutNewEvents() {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(7);
        QueueRosterControlCommand<SubmitQueueIntent> command = command(
                submit("queue-1", List.of(SUBJECT_1), 1, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT,
                "cmd-submit-1",
                "idem-submit-1",
                BASE_TIME,
                Optional.empty(),
                PRINCIPAL_ID);

        QueueRosterDecision first = controller.handle(command, record);
        QueueRosterDecision second = controller.handle(command, first.record());

        assertEquals(QueueRosterDecisionStatus.ACCEPTED, first.status());
        assertEquals(QueueRosterDecisionStatus.REPLAYED, second.status());
        assertEquals(first.receipt(), second.receipt());
        assertTrue(second.events().isEmpty());
        assertTrue(second.emissions().isEmpty());
    }

    @Test
    void cancelledIntentCannotBeRostered() {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(7);

        record = acceptedRecord(controller, record, submit("queue-1", List.of(SUBJECT_1), 1, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-1", BASE_TIME);
        record = acceptedRecord(controller, record, new CancelQueueIntent(PARTITION_KEY, queueId("queue-1"), BASE_TIME.plusSeconds(1)),
                ControlQueueNames.CANCEL_QUEUE_INTENT, "cmd-cancel-1", BASE_TIME.plusSeconds(1));
        QueueRosterDecision decision = controller.handle(command(
                form("roster-1", List.of(queueId("queue-1")), 1, 2),
                ControlQueueNames.FORM_ROSTER_INTENT,
                "cmd-form",
                "idem-form",
                BASE_TIME.plusSeconds(2),
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);

        assertEquals(QueueRosterDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(QueueRosterRejectionReason.QUEUE_INTENT_NOT_WAITING), decision.receipt().rejectionReason());
        assertTrue(record.state().rosterIntents().isEmpty());
    }

    @Test
    void expiredIntentCannotBeRostered() {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(7);

        record = acceptedRecord(controller, record, submit("queue-1", List.of(SUBJECT_1), 1, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-1", BASE_TIME);
        record = acceptedRecord(controller, record, new ExpireQueueIntent(PARTITION_KEY, queueId("queue-1"), BASE_TIME.plusSeconds(1)),
                ControlQueueNames.EXPIRE_QUEUE_INTENT, "cmd-expire-1", BASE_TIME.plusSeconds(1));
        QueueRosterDecision decision = controller.handle(command(
                form("roster-1", List.of(queueId("queue-1")), 1, 2),
                ControlQueueNames.FORM_ROSTER_INTENT,
                "cmd-form",
                "idem-form",
                BASE_TIME.plusSeconds(2),
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);

        assertEquals(QueueRosterDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(QueueRosterRejectionReason.QUEUE_INTENT_NOT_WAITING), decision.receipt().rejectionReason());
        assertTrue(record.state().rosterIntents().isEmpty());
    }

    @Test
    void duplicateSubjectInWaitingQueueIsRejected() {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(7);

        record = acceptedRecord(controller, record, submit("queue-1", List.of(SUBJECT_1), 1, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-1", BASE_TIME);
        QueueRosterDecision decision = controller.handle(command(
                submit("queue-2", List.of(SUBJECT_1), 1, 1),
                ControlQueueNames.SUBMIT_QUEUE_INTENT,
                "cmd-submit-2",
                "idem-submit-2",
                BASE_TIME.plusSeconds(1),
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);

        assertEquals(QueueRosterDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(QueueRosterRejectionReason.DUPLICATE_SUBJECT_IN_WAITING_QUEUE), decision.receipt().rejectionReason());
        assertEquals(1, record.state().queueIntents().size());
    }

    @Test
    void rosterFormationIsDeterministicForSameIntentSet() {
        QueueRosterControlRecord firstRecord = submittedRecord();
        QueueRosterControlRecord secondRecord = submittedRecord();
        QueueRosterController firstController = new QueueRosterController();
        QueueRosterController secondController = new QueueRosterController();

        firstRecord = acceptedRecord(firstController, firstRecord, form("roster-1", List.of(queueId("queue-1"), queueId("queue-2"), queueId("queue-3")), 3, 4),
                ControlQueueNames.FORM_ROSTER_INTENT, "cmd-form-1", BASE_TIME.plusSeconds(4));
        secondRecord = acceptedRecord(secondController, secondRecord, form("roster-2", List.of(queueId("queue-3"), queueId("queue-1"), queueId("queue-2")), 3, 4),
                ControlQueueNames.FORM_ROSTER_INTENT, "cmd-form-2", BASE_TIME.plusSeconds(4));

        RosterIntentSnapshot firstRoster = firstRecord.state().rosterIntent(new RosterIntentId("roster-1")).orElseThrow();
        RosterIntentSnapshot secondRoster = secondRecord.state().rosterIntent(new RosterIntentId("roster-2")).orElseThrow();
        assertEquals(List.of(queueId("queue-2"), queueId("queue-3"), queueId("queue-1")), firstRoster.queueIntentIds());
        assertEquals(firstRoster.queueIntentIds(), secondRoster.queueIntentIds());
        assertEquals(firstRoster.subjectIds(), secondRoster.subjectIds());
    }

    @Test
    void principalMismatchRejectsBeforeMutation() {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(7);

        QueueRosterDecision decision = controller.handle(command(
                submit("queue-1", List.of(SUBJECT_1), 1, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT,
                "cmd-submit-1",
                "idem-submit-1",
                BASE_TIME,
                Optional.empty(),
                new PrincipalId("principal-attacker")), record);

        assertEquals(QueueRosterDecisionStatus.REJECTED, decision.status());
        assertEquals(Optional.of(QueueRosterRejectionReason.PRINCIPAL_MISMATCH), decision.receipt().rejectionReason());
        assertTrue(record.state().queueIntents().isEmpty());
    }

    private static QueueRosterControlRecord submittedRecord() {
        QueueRosterController controller = new QueueRosterController();
        QueueRosterControlRecord record = QueueRosterController.emptyRecord(7);
        record = acceptedRecord(controller, record, submit("queue-1", List.of(SUBJECT_1), 1, 0),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-1", BASE_TIME);
        record = acceptedRecord(controller, record, submit("queue-2", List.of(SUBJECT_2), 3, 1),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-2", BASE_TIME.plusSeconds(1));
        return acceptedRecord(controller, record, submit("queue-3", List.of(SUBJECT_3), 2, 2),
                ControlQueueNames.SUBMIT_QUEUE_INTENT, "cmd-submit-3", BASE_TIME.plusSeconds(2));
    }

    private static QueueRosterDecision accept(
            QueueRosterController controller,
            QueueRosterControlRecord record,
            QueueRosterCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt,
            List<QueueRosterEvent> events) {
        QueueRosterDecision decision = controller.handle(command(
                payload,
                commandName,
                commandId,
                "idem-" + commandId,
                receivedAt,
                Optional.of(record.revision()),
                PRINCIPAL_ID), record);
        assertEquals(QueueRosterDecisionStatus.ACCEPTED, decision.status());
        events.addAll(decision.events());
        return decision;
    }

    private static QueueRosterControlRecord acceptedRecord(
            QueueRosterController controller,
            QueueRosterControlRecord record,
            QueueRosterCommand payload,
            CommandName commandName,
            String commandId,
            Instant receivedAt) {
        return accept(controller, record, payload, commandName, commandId, receivedAt, new ArrayList<>()).record();
    }

    private static <T extends QueueRosterCommand> QueueRosterControlCommand<T> command(
            T payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal) {
        return new QueueRosterControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL_ID,
                        aggregateId(payload),
                        ControlQueueNames.CONTRACT,
                        commandName,
                        trace(),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                authenticatedPrincipal,
                7,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static AggregateId aggregateId(QueueRosterCommand payload) {
        return ControlQueueNames.aggregateId(payload.partitionKey());
    }

    private static SubmitQueueIntent submit(String queueIntentId, List<SubjectId> subjectIds, int priority, long createdOffsetSeconds) {
        Instant createdAt = BASE_TIME.plusSeconds(createdOffsetSeconds);
        return new SubmitQueueIntent(
                queueId(queueIntentId),
                subjectIds,
                EXPERIENCE_ID,
                MODE_ID,
                POOL_ID,
                priority,
                createdAt,
                BASE_TIME.plusSeconds(60),
                trace());
    }

    private static FormRosterIntent form(String rosterIntentId, List<QueueIntentId> queueIntentIds, int maxSubjects, long formedOffsetSeconds) {
        return new FormRosterIntent(
                new RosterIntentId(rosterIntentId),
                PARTITION_KEY,
                queueIntentIds,
                maxSubjects,
                BASE_TIME.plusSeconds(formedOffsetSeconds),
                trace());
    }

    private static QueueIntentId queueId(String value) {
        return new QueueIntentId(value);
    }

    private static SubjectId subject(String value) {
        return new SubjectId(UUID.fromString(value));
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-queue-roster",
                "span-queue-roster",
                Optional.empty(),
                BASE_TIME,
                "queue-controller-test",
                new InstanceId("instance-controller-queue"));
    }
}
