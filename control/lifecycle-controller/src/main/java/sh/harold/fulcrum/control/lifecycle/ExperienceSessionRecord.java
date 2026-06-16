package sh.harold.fulcrum.control.lifecycle;

import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ExperienceSessionRecord(
        SessionId sessionId,
        ExperienceId experienceId,
        Optional<String> modeId,
        String sessionType,
        List<SubjectId> subjectIds,
        Optional<SlotId> allocationSlotId,
        Optional<InstanceId> instanceId,
        Optional<ResolvedManifestId> resolvedManifestId,
        ExperienceSessionStatus status,
        Instant createdAt,
        Optional<Instant> activatedAt,
        Optional<Instant> endedAt,
        Optional<String> endReason,
        TraceEnvelope traceEnvelope,
        Instant updatedAt) {
    public ExperienceSessionRecord {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        experienceId = Objects.requireNonNull(experienceId, "experienceId");
        modeId = modeId == null
                ? Optional.empty()
                : modeId.map(value -> ControlLifecycleStrings.requireNonBlank(value, "modeId"));
        sessionType = ControlLifecycleStrings.requireNonBlank(sessionType, "sessionType");
        subjectIds = List.copyOf(Objects.requireNonNull(subjectIds, "subjectIds"));
        if (subjectIds.isEmpty()) {
            throw new IllegalArgumentException("subjectIds must not be empty");
        }
        if (new HashSet<>(subjectIds).size() != subjectIds.size()) {
            throw new IllegalArgumentException("subjectIds must not contain duplicates");
        }
        allocationSlotId = allocationSlotId == null ? Optional.empty() : allocationSlotId;
        instanceId = instanceId == null ? Optional.empty() : instanceId;
        resolvedManifestId = resolvedManifestId == null ? Optional.empty() : resolvedManifestId;
        status = Objects.requireNonNull(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        activatedAt = activatedAt == null ? Optional.empty() : activatedAt;
        endedAt = endedAt == null ? Optional.empty() : endedAt;
        endReason = endReason == null
                ? Optional.empty()
                : endReason.map(reason -> ControlLifecycleStrings.requireNonBlank(reason, "endReason"));
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static ExperienceSessionRecord from(RequestExperienceSession command) {
        return new ExperienceSessionRecord(
                command.sessionId(),
                command.experienceId(),
                command.modeId(),
                command.sessionType(),
                command.subjectIds(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ExperienceSessionStatus.REQUESTED,
                command.requestedAt(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                command.traceEnvelope(),
                command.requestedAt());
    }

    ExperienceSessionRecord place(PlaceExperienceSession command) {
        return transition(
                Optional.of(command.allocationSlotId()),
                Optional.of(command.instanceId()),
                Optional.of(command.resolvedManifestId()),
                ExperienceSessionStatus.PLACED,
                activatedAt,
                endedAt,
                endReason,
                command.traceEnvelope(),
                command.placedAt());
    }

    ExperienceSessionRecord activate(ActivateExperienceSession command) {
        return transition(
                allocationSlotId,
                instanceId,
                resolvedManifestId,
                ExperienceSessionStatus.ACTIVE,
                Optional.of(command.activatedAt()),
                endedAt,
                endReason,
                command.traceEnvelope(),
                command.activatedAt());
    }

    ExperienceSessionRecord end(EndExperienceSession command) {
        return transition(
                allocationSlotId,
                instanceId,
                resolvedManifestId,
                ExperienceSessionStatus.ENDED,
                activatedAt,
                Optional.of(command.endedAt()),
                Optional.of(command.endReason()),
                command.traceEnvelope(),
                command.endedAt());
    }

    private ExperienceSessionRecord transition(
            Optional<SlotId> nextAllocationSlotId,
            Optional<InstanceId> nextInstanceId,
            Optional<ResolvedManifestId> nextResolvedManifestId,
            ExperienceSessionStatus nextStatus,
            Optional<Instant> nextActivatedAt,
            Optional<Instant> nextEndedAt,
            Optional<String> nextEndReason,
            TraceEnvelope nextTraceEnvelope,
            Instant updatedAt) {
        return new ExperienceSessionRecord(
                sessionId,
                experienceId,
                modeId,
                sessionType,
                subjectIds,
                nextAllocationSlotId,
                nextInstanceId,
                nextResolvedManifestId,
                nextStatus,
                createdAt,
                nextActivatedAt,
                nextEndedAt,
                nextEndReason,
                nextTraceEnvelope,
                updatedAt);
    }

    public String wireValue(Revision revision) {
        return "sessionId=" + sessionId.value()
                + "|experienceId=" + experienceId.value()
                + "|sessionType=" + sessionType
                + "|subjectCount=" + subjectIds.size()
                + "|slotId=" + allocationSlotId.map(SlotId::value).orElse("none")
                + "|instanceId=" + instanceId.map(InstanceId::value).orElse("none")
                + "|resolvedManifestId=" + resolvedManifestId.map(ResolvedManifestId::value).orElse("none")
                + "|status=" + status.name()
                + "|revision=" + revision.value()
                + "|traceId=" + traceEnvelope.traceId();
    }
}
