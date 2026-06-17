package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.util.List;
import java.util.Set;

final class RuntimeIdentityIssuer {
    private RuntimeIdentityIssuer() {
    }

    static HostSecurityContext issue(ProfileDescriptor profile, LaunchEntry entry, RuntimeEnvironment environment) {
        String roleId = entry.role().id();
        String instanceKind = instanceKind(entry.role());
        String machineRef = environment.value("FULCRUM_MACHINE_REF").orElse("machine-local");
        HostInstanceIdentity identity = new HostInstanceIdentity(
                new InstanceId("instance-" + profile.profileId() + "-" + roleId),
                instanceKind,
                new PoolId("pool-" + profile.profileId() + "-" + roleId),
                new MachineRef(machineRef),
                new PrincipalId("principal-" + profile.profileId() + "-" + roleId));
        return new HostSecurityContext(
                identity,
                "service-account:" + roleId,
                new HostCredentialScope(grants(entry.role())));
    }

    private static String instanceKind(LaunchRole role) {
        return switch (role) {
            case AUTHORITY_SERVICE -> "authority";
            case CONTROLLER_SERVICE -> "controller";
            case WORKER_AGENT -> HostInstanceKinds.WORKER;
            case PAPER_AGENT -> HostInstanceKinds.PAPER;
            case VELOCITY_AGENT -> HostInstanceKinds.VELOCITY;
            case ALL -> throw new IllegalArgumentException("ALL is not a runtime Instance kind");
        };
    }

    private static Set<HostResourceGrant> grants(LaunchRole role) {
        return Set.copyOf(switch (role) {
            case AUTHORITY_SERVICE -> List.of(
                    grant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "cmd.*"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "evt.*"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "state.*"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "rsp.*"));
            case CONTROLLER_SERVICE -> List.of(
                    grant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "ctrl.cmd.*"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "ctrl.evt.*"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "ctrl.state.*"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "host.route.*"));
            case WORKER_AGENT -> List.of(
                    grant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "worker.jobs"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "worker.results"),
                    grant(HostResourceFamily.ARTIFACT, HostAccessMode.READ, "artifact.*"));
            case PAPER_AGENT -> List.of(
                    grant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "host.paper.commands"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.session"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "host.observation"),
                    grant(HostResourceFamily.CACHE, HostAccessMode.READ, "session.*"),
                    grant(HostResourceFamily.HOT_PROJECTION, HostAccessMode.READ, "standard.rank.effective"),
                    grant(HostResourceFamily.HOT_PROJECTION, HostAccessMode.READ, "standard.player-profile.summary"),
                    grant(HostResourceFamily.ARTIFACT, HostAccessMode.READ, "artifact.lobby-bedrock"));
            case VELOCITY_AGENT -> List.of(
                    grant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "host.velocity.routes"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.presence"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.route"),
                    grant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "host.observation"),
                    grant(HostResourceFamily.CACHE, HostAccessMode.READ, "standard.punishment.active"),
                    grant(HostResourceFamily.HOT_PROJECTION, HostAccessMode.READ, "standard.punishment.active"));
            case ALL -> throw new IllegalArgumentException("ALL does not have one credential scope");
        });
    }

    private static HostResourceGrant grant(HostResourceFamily family, HostAccessMode mode, String name) {
        return new HostResourceGrant(family, mode, name);
    }
}
