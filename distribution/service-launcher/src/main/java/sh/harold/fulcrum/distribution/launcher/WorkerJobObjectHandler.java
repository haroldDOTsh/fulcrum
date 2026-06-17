package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.adapters.objectstorage.ObjectStorageAdapter;
import sh.harold.fulcrum.adapters.objectstorage.StoredObject;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.host.worker.WorkerJobHandler;
import sh.harold.fulcrum.host.worker.WorkerJobKind;
import sh.harold.fulcrum.host.worker.WorkerJobRequest;
import sh.harold.fulcrum.host.worker.WorkerJobResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class WorkerJobObjectHandler implements WorkerJobHandler {
    private final ObjectStorageAdapter objectStorage;

    WorkerJobObjectHandler(ObjectStorageAdapter objectStorage) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    @Override
    public WorkerJobResult handle(WorkerJobRequest request) {
        byte[] bytes = outputBytes(request);
        ArtifactPin pin = new ArtifactPin(
                new ArtifactId("worker-output." + workerDomain(request.jobKind()) + "." + request.jobId().value()),
                sha256(bytes),
                "worker-job-output-v1");
        try {
            StoredObject stored = objectStorage.put(pin, bytes);
            return new WorkerJobResult("accepted", stored.address().value());
        } catch (IOException exception) {
            throw new IllegalStateException("Worker job output storage failed", exception);
        }
    }

    static String workerDomain(WorkerJobKind kind) {
        return Objects.requireNonNull(kind, "kind").name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static byte[] outputBytes(WorkerJobRequest request) {
        String output = String.join("\n", List.of(
                "jobId=" + request.jobId().value(),
                "jobKind=" + workerDomain(request.jobKind()),
                "workKey=" + request.workKey(),
                "resolvedManifestId=" + request.resolvedManifestId().value(),
                "traceId=" + request.traceEnvelope().traceId(),
                "payloadFingerprint=" + request.payloadFingerprint()));
        return output.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
