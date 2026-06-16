package sh.harold.fulcrum.adapters.objectstorage;

import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactDigestReference;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class LocalObjectStorageAdapter implements ObjectStorageAdapter {
    private static final String OBJECT_SCHEME = "object://";

    private final Path root;
    private final Path bucketRoot;
    private final String bucket;

    public LocalObjectStorageAdapter(Path root, String bucket) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.bucket = requireBucket(bucket);
        this.bucketRoot = this.root.resolve(this.bucket).normalize();
        if (!bucketRoot.startsWith(this.root)) {
            throw new IllegalArgumentException("bucket root escaped the object storage root");
        }
    }

    @Override
    public StoredObject put(ArtifactPin artifactPin, byte[] bytes) throws IOException {
        Objects.requireNonNull(artifactPin, "artifactPin");
        byte[] copiedBytes = Objects.requireNonNull(bytes, "bytes").clone();
        ArtifactDigestReference digest = verifiedDigest(artifactPin, copiedBytes);
        ArtifactObjectAddress address = ArtifactBlobLayout.objectAddress(bucket, artifactPin);
        Path path = pathFor(address);
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            byte[] existingBytes = Files.readAllBytes(path);
            if (!Arrays.equals(existingBytes, copiedBytes)) {
                throw new IOException("object address already contains different bytes");
            }
            return new StoredObject(address, existingBytes.length, digest);
        }
        Files.write(path, copiedBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new StoredObject(address, copiedBytes.length, digest);
    }

    @Override
    public Optional<byte[]> read(ArtifactObjectAddress address) throws IOException {
        Path path = pathFor(address);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(path));
    }

    @Override
    public boolean exists(ArtifactObjectAddress address) throws IOException {
        return Files.exists(pathFor(address));
    }

    private Path pathFor(ArtifactObjectAddress address) {
        Objects.requireNonNull(address, "address");
        String prefix = OBJECT_SCHEME + bucket + "/";
        if (!address.value().startsWith(prefix)) {
            throw new IllegalArgumentException("object address is outside the configured bucket");
        }
        String relative = address.value().substring(prefix.length());
        if (relative.isBlank() || relative.contains("\\") || relative.contains("..")) {
            throw new IllegalArgumentException("object address contains an invalid relative path");
        }
        Path path = bucketRoot.resolve(relative).normalize();
        if (!path.startsWith(bucketRoot)) {
            throw new IllegalArgumentException("object address escaped the configured bucket");
        }
        return path;
    }

    private static ArtifactDigestReference verifiedDigest(ArtifactPin artifactPin, byte[] bytes) {
        ArtifactDigestReference digest = ArtifactBlobLayout.digestFor(artifactPin);
        if (!digest.algorithm().equals("sha-256")) {
            throw new IllegalArgumentException("local object storage supports sha-256 artifact pins");
        }
        String actualDigest = sha256(bytes);
        if (!actualDigest.equals(digest.value())) {
            throw new IllegalArgumentException("artifact bytes do not match the pinned digest");
        }
        return digest;
    }

    private static String requireBucket(String bucket) {
        String checked = Objects.requireNonNull(bucket, "bucket").trim().toLowerCase(Locale.ROOT);
        ArtifactBlobLayout.objectAddress(checked, new ArtifactPin(
                new sh.harold.fulcrum.api.kernel.ArtifactId("artifact.bucket.validation"),
                "0".repeat(64),
                "validation"));
        return checked;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
