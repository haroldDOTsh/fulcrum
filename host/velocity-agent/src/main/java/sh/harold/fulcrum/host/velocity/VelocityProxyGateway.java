package sh.harold.fulcrum.host.velocity;

import sh.harold.fulcrum.api.kernel.SubjectId;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletionStage;

public interface VelocityProxyGateway {
    void registerBackend(String backendName, InetSocketAddress address);

    CompletionStage<Boolean> transfer(SubjectId subjectId, String backendName);
}
