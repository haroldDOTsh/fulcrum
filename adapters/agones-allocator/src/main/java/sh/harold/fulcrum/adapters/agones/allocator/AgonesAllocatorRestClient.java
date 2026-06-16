package sh.harold.fulcrum.adapters.agones.allocator;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationPort;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class AgonesAllocatorRestClient implements HostAllocationPort {
    private static final String ALLOCATION_PATH = "/gameserverallocation";

    private final URI allocatorEndpoint;
    private final String namespace;
    private final HttpClient httpClient;

    public AgonesAllocatorRestClient(URI allocatorEndpoint, String namespace) {
        this(allocatorEndpoint, namespace, HttpClient.newHttpClient());
    }

    public AgonesAllocatorRestClient(URI allocatorEndpoint, String namespace, HttpClient httpClient) {
        this.allocatorEndpoint = Objects.requireNonNull(allocatorEndpoint, "allocatorEndpoint");
        this.namespace = AgonesAllocatorJson.requireNonBlank(namespace, "namespace");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public HostAllocationClaim allocate(HostAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        String requestBody = AgonesAllocatorJson.allocationRequest(namespace, request);
        HttpRequest httpRequest = HttpRequest.newBuilder(allocatorEndpoint.resolve(ALLOCATION_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agones allocation request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Agones allocation request failed", exception);
        }

        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
            throw new IllegalStateException("Agones allocation failed with HTTP " + httpResponse.statusCode());
        }

        AgonesAllocationResponse allocation = AgonesAllocatorJson.allocationResponse(httpResponse.body());
        if (!HostInstanceKinds.PAPER.equals(allocation.instanceKind())) {
            throw new IllegalStateException("Agones allocation returned non-Paper Instance: " + allocation.instanceKind());
        }

        HostInstanceIdentity instanceIdentity = new HostInstanceIdentity(
                new InstanceId(allocation.instanceId()),
                HostInstanceKinds.PAPER,
                request.poolId(),
                new MachineRef(allocation.machineRef()),
                new PrincipalId(allocation.principalId()));

        return new HostAllocationClaim(
                new SlotId(allocation.slotId()),
                request.sessionId(),
                instanceIdentity,
                request.resolvedManifestId(),
                request.traceEnvelope(),
                request.requestedAt());
    }
}
