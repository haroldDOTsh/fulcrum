package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.instance.InstanceRegistryStatus;
import sh.harold.fulcrum.control.instance.InstanceSnapshot;
import sh.harold.fulcrum.control.instance.SharedShardOccupancySnapshot;
import sh.harold.fulcrum.control.instance.SharedShardPlacementCandidate;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.velocity.VelocityBackendEndpoint;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class VelocitySharedShardAllocationRegistry {
    private final Map<InstanceId, HostAllocationClaim> claims = new ConcurrentHashMap<>();
    private final Map<SessionId, Set<PresenceId>> routedPresences = new ConcurrentHashMap<>();

    void record(ExternalControllerWorkerCatalog.StoredSharedShardAllocation allocation) {
        Objects.requireNonNull(allocation, "allocation");
        HostAllocationClaim claim = allocation.claim();
        claims.put(claim.instanceIdentity().instanceId(), claim);
    }

    void recordRoutedPresence(SessionId sessionId, PresenceId presenceId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(presenceId, "presenceId");
        routedPresences
                .computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet())
                .add(presenceId);
    }

    void recordRoutedSubject(SessionId sessionId, SubjectId subjectId) {
        recordRoutedPresence(sessionId, presenceId(subjectId));
    }

    VelocityBackendEndpoint backend(InstanceId targetInstanceId) {
        HostAllocationClaim claim = claims.get(Objects.requireNonNull(targetInstanceId, "targetInstanceId"));
        if (claim == null) {
            throw new IllegalStateException("No allocated Paper endpoint for Instance " + targetInstanceId.value());
        }
        return new VelocityBackendEndpoint(
                claim.instanceIdentity().instanceId(),
                claim.minecraftEndpoint().host(),
                claim.minecraftEndpoint().port());
    }

    List<SharedShardPlacementCandidate> placementCandidates(
            RuntimeConnectionSettings.VelocityConnections settings,
            TraceEnvelope trace,
            Instant observedAt) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(observedAt, "observedAt");
        return claims.values().stream()
                .filter(claim -> settings.lobbyPoolId().equals(claim.instanceIdentity().poolId()))
                .filter(claim -> settings.lobbyResolvedManifestId().equals(claim.resolvedManifestId()))
                .sorted(Comparator.comparing(claim -> claim.instanceIdentity().instanceId().value()))
                .map(claim -> candidate(claim, settings, trace, observedAt))
                .toList();
    }

    private SharedShardPlacementCandidate candidate(
            HostAllocationClaim claim,
            RuntimeConnectionSettings.VelocityConnections settings,
            TraceEnvelope trace,
            Instant observedAt) {
        HostInstanceIdentity identity = claim.instanceIdentity();
        return new SharedShardPlacementCandidate(
                new InstanceSnapshot(
                        identity.instanceId(),
                        identity.instanceKind(),
                        identity.poolId(),
                        identity.machineRef(),
                        identity.principalId(),
                        Optional.of(claim.resolvedManifestId()),
                        InstanceRegistryStatus.READY,
                        Optional.empty(),
                        trace,
                        observedAt),
                new SharedShardOccupancySnapshot(
                        claim.sessionId(),
                        claim.slotId(),
                        currentPresences(claim, settings),
                        settings.lobbyHardCapacity(),
                        true,
                        observedAt,
                        trace));
    }

    private int currentPresences(
            HostAllocationClaim claim,
            RuntimeConnectionSettings.VelocityConnections settings) {
        Set<PresenceId> presences = routedPresences.get(claim.sessionId());
        if (presences == null) {
            return 0;
        }
        return Math.min(presences.size(), settings.lobbyHardCapacity());
    }

    private static PresenceId presenceId(SubjectId subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        return new PresenceId("presence-velocity-login-" + subjectId.value().toString().replace("-", ""));
    }
}
