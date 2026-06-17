package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.data.store.valkey.ValkeyClientHandle;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateDecision;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateEvaluator;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateRequest;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginDecision;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginGate;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginRequest;
import sh.harold.fulcrum.standard.punishment.PunishmentState;

import java.util.Objects;
import java.util.Optional;

final class ValkeyPunishmentLoginGateEvaluator implements VelocityLoginGateEvaluator {
    private static final String ACTIVE_PUNISHMENT_CACHE_RESOURCE = "standard.punishment.active";

    private final HostSecurityContext securityContext;
    private final ValkeyClientHandle valkey;
    private final String loginGateScope;

    ValkeyPunishmentLoginGateEvaluator(
            HostSecurityContext securityContext,
            ValkeyClientHandle valkey,
            String loginGateScope) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.valkey = Objects.requireNonNull(valkey, "valkey");
        this.loginGateScope = requireNonBlank(loginGateScope, "loginGateScope");
        if (!HostInstanceKinds.VELOCITY.equals(securityContext.identity().instanceKind())) {
            throw new IllegalArgumentException("Velocity login gate requires a Velocity Instance identity");
        }
        if (!securityContext.credentialScope().permits(
                HostResourceFamily.CACHE,
                HostAccessMode.READ,
                ACTIVE_PUNISHMENT_CACHE_RESOURCE)) {
            throw new SecurityException("Velocity Instance is not allowed to read active punishment cache");
        }
    }

    @Override
    public VelocityLoginGateDecision evaluate(VelocityLoginGateRequest request) {
        Objects.requireNonNull(request, "request");
        if (!loginGateScope.equals(request.loginGateScope())) {
            throw new SecurityException("Velocity login gate scope is not allowed");
        }
        String payload = valkey.client().get(PunishmentAuthority.cacheKey(request.subjectId()));
        Optional<PunishmentState> state = payload == null || payload.isBlank()
                ? Optional.empty()
                : Optional.of(PunishmentState.parse(payload));
        PunishmentLoginDecision decision = PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(request.subjectId(), request.attemptedAt()),
                state.flatMap(PunishmentState::active));
        return decision.allowed()
                ? VelocityLoginGateDecision.allowed(decision.subjectId())
                : VelocityLoginGateDecision.denied(decision.subjectId(), decision.denialReason().orElseThrow());
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
