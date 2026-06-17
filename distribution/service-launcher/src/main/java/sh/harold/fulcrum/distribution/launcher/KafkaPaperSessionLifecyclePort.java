package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.session.ActivateSession;
import sh.harold.fulcrum.data.session.OpenSession;
import sh.harold.fulcrum.data.session.SessionAuthority;
import sh.harold.fulcrum.data.session.SessionCommand;
import sh.harold.fulcrum.data.session.SessionOwnerToken;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.paper.PaperSessionActivationRequest;
import sh.harold.fulcrum.host.paper.PaperSessionLifecyclePort;
import sh.harold.fulcrum.host.paper.PaperSessionOpenRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

final class KafkaPaperSessionLifecyclePort implements PaperSessionLifecyclePort {
    static final String SESSION_COMMAND_TOPIC = "cmd.session";
    private static final long AUTHORITY_FENCING_EPOCH = 1;

    private final HostSecurityContext securityContext;
    private final Producer<String, String> producer;

    KafkaPaperSessionLifecyclePort(HostSecurityContext securityContext, Producer<String, String> producer) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.producer = Objects.requireNonNull(producer, "producer");
    }

    @Override
    public void openSession(PaperSessionOpenRequest request) {
        Objects.requireNonNull(request, "request");
        OpenSession payload = new OpenSession(
                request.sessionId(),
                request.experienceId(),
                request.slotId(),
                request.ownerInstanceId(),
                new SessionOwnerToken(request.ownerToken()),
                request.resolvedManifestId(),
                request.openedAt(),
                request.leaseExpiresAt());
        AuthorityCommand<SessionCommand> command = command(
                request,
                SessionAuthorityWireCodec.OPEN_COMMAND,
                payload,
                request.openedAt(),
                Optional.of(new Revision(0)));
        publish(command);
    }

    @Override
    public void activateSession(PaperSessionActivationRequest request) {
        Objects.requireNonNull(request, "request");
        ActivateSession payload = new ActivateSession(
                request.sessionId(),
                new SessionOwnerToken(request.ownerToken()),
                request.ownerEpoch(),
                request.activatedAt(),
                request.leaseExpiresAt());
        AuthorityCommand<SessionCommand> command = command(
                request,
                SessionAuthorityWireCodec.ACTIVATE_COMMAND,
                payload,
                request.activatedAt(),
                Optional.of(new Revision(1)));
        publish(command);
    }

    private AuthorityCommand<SessionCommand> command(
            PaperSessionOpenRequest request,
            String commandName,
            SessionCommand payload,
            Instant receivedAt,
            Optional<Revision> expectedRevision) {
        PrincipalId principalId = securityContext.identity().principalId();
        CommandEnvelope<SessionCommand> envelope = new CommandEnvelope<>(
                commandId(commandName, payload.sessionId().value(), receivedAt),
                idempotencyKey(commandName, payload.sessionId().value(), receivedAt),
                principalId,
                SessionAuthority.aggregateId(payload.sessionId()),
                new ContractName(SessionAuthorityWireCodec.CONTRACT),
                new CommandName(commandName),
                request.traceEnvelope(),
                Optional.of(request.leaseExpiresAt()),
                payload);
        return new AuthorityCommand<>(
                envelope,
                principalId,
                AUTHORITY_FENCING_EPOCH,
                expectedRevision,
                fingerprint(commandName, payload),
                receivedAt);
    }

    private AuthorityCommand<SessionCommand> command(
            PaperSessionActivationRequest request,
            String commandName,
            SessionCommand payload,
            Instant receivedAt,
            Optional<Revision> expectedRevision) {
        PrincipalId principalId = securityContext.identity().principalId();
        CommandEnvelope<SessionCommand> envelope = new CommandEnvelope<>(
                commandId(commandName, payload.sessionId().value(), receivedAt),
                idempotencyKey(commandName, payload.sessionId().value(), receivedAt),
                principalId,
                SessionAuthority.aggregateId(payload.sessionId()),
                new ContractName(SessionAuthorityWireCodec.CONTRACT),
                new CommandName(commandName),
                request.traceEnvelope(),
                Optional.of(request.leaseExpiresAt()),
                payload);
        return new AuthorityCommand<>(
                envelope,
                principalId,
                AUTHORITY_FENCING_EPOCH,
                expectedRevision,
                fingerprint(commandName, payload),
                receivedAt);
    }

    private void publish(AuthorityCommand<SessionCommand> command) {
        requireSessionCommandGrant();
        try {
            producer.send(new ProducerRecord<>(
                    SESSION_COMMAND_TOPIC,
                    command.envelope().aggregateId().value(),
                    SessionAuthorityWireCodec.encodeCommand(command))).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing Paper Session command", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Could not publish Paper Session command to " + SESSION_COMMAND_TOPIC, exception);
        }
    }

    private void requireSessionCommandGrant() {
        if (!securityContext.credentialScope().permits(
                HostResourceFamily.TOPIC,
                HostAccessMode.PRODUCE,
                SESSION_COMMAND_TOPIC)) {
            throw new SecurityException("Paper Instance is not allowed to produce Session commands");
        }
    }

    private static CommandId commandId(String commandName, String sessionId, Instant receivedAt) {
        return new CommandId("command-paper-" + commandName + "-" + sessionId + "-" + receivedAt.toEpochMilli());
    }

    private static IdempotencyKey idempotencyKey(String commandName, String sessionId, Instant receivedAt) {
        return new IdempotencyKey("idem-paper-" + commandName + "-" + sessionId + "-" + receivedAt.toEpochMilli());
    }

    private static String fingerprint(String commandName, SessionCommand payload) {
        StringBuilder builder = new StringBuilder()
                .append("commandName=").append(commandName).append('\n')
                .append("sessionId=").append(payload.sessionId().value()).append('\n');
        if (payload instanceof OpenSession open) {
            builder.append("experienceId=").append(open.experienceId().value()).append('\n')
                    .append("slotId=").append(open.slotId().value()).append('\n')
                    .append("ownerInstanceId=").append(open.ownerInstanceId().value()).append('\n')
                    .append("ownerToken=").append(open.ownerToken().value()).append('\n')
                    .append("resolvedManifestId=").append(open.resolvedManifestId().value()).append('\n')
                    .append("openedAt=").append(open.openedAt()).append('\n')
                    .append("leaseExpiresAt=").append(open.leaseExpiresAt()).append('\n');
        } else if (payload instanceof ActivateSession activate) {
            builder.append("ownerToken=").append(activate.ownerToken().value()).append('\n')
                    .append("ownerEpoch=").append(activate.ownerEpoch()).append('\n')
                    .append("activatedAt=").append(activate.activatedAt()).append('\n')
                    .append("leaseExpiresAt=").append(activate.leaseExpiresAt()).append('\n');
        } else {
            throw new IllegalArgumentException("Unsupported Paper Session lifecycle payload " + payload.getClass().getName());
        }
        return sha256(builder.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
