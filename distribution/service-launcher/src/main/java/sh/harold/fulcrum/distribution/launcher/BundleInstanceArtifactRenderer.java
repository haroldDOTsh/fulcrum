package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

final class BundleInstanceArtifactRenderer {
    private static final String DIRECTORY_NAME = "bundle-instances";

    private final Path stateDir;

    BundleInstanceArtifactRenderer(Path stateDir) {
        this.stateDir = Objects.requireNonNull(stateDir, "stateDir");
    }

    BundleRenderedInstance describe(BundleInstanceManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        String slot = safeName(manifest.instanceId());
        Path workDir = stateDir.resolve(DIRECTORY_NAME).resolve(slot);
        Path envDir = workDir.resolve("env");
        Path manifestFile = workDir.resolve("manifest.json");
        Path envFile = envDir.resolve("bootstrap.env");
        Path composeFile = workDir.resolve("compose.yaml");
        Path helmChartDir = workDir.resolve("helm");
        Path helmDeploymentFile = helmChartDir.resolve("templates").resolve("backend-deployment.yaml");
        Path checksumsFile = workDir.resolve("checksums.txt");
        String manifestHash = manifest.manifestHash();
        return new BundleRenderedInstance(
                workDir,
                manifestFile,
                envFile,
                composeFile,
                helmChartDir,
                helmDeploymentFile,
                checksumsFile,
                manifestHash);
    }

    BundleRenderedInstance render(BundleInstanceManifest manifest, String launchNonce) {
        Objects.requireNonNull(manifest, "manifest");
        String checkedLaunchNonce = BundleLaunchNonces.require(launchNonce);
        BundleRenderedInstance rendered = describe(manifest);
        Path envDir = rendered.envFile().getParent();
        Path runtimeDir = rendered.workDir().resolve("runtime");
        String manifestHash = rendered.manifestHash();
        String manifestJson = manifest.canonicalJson() + System.lineSeparator();
        String env = envContent(manifest, manifestHash, checkedLaunchNonce);
        String compose = composeContent(manifest, manifestHash);
        String helmChart = helmChartContent(manifest);
        String helmValues = helmValuesContent(manifest, manifestHash);
        String helmDeployment = helmDeploymentContent(manifest, manifestHash, checkedLaunchNonce);
        String checksums = checksumsContent(manifestHash, manifestJson, env, compose, helmChart, helmValues, helmDeployment);
        try {
            Files.createDirectories(envDir);
            Files.createDirectories(runtimeDir);
            Files.createDirectories(rendered.helmDeploymentFile().getParent());
            writeAtomically(rendered.manifestFile(), manifestJson);
            writeAtomically(rendered.envFile(), env);
            writeAtomically(rendered.composeFile(), compose);
            writeAtomically(rendered.helmChartDir().resolve("Chart.yaml"), helmChart);
            writeAtomically(rendered.helmChartDir().resolve("values.yaml"), helmValues);
            writeAtomically(rendered.helmDeploymentFile(), helmDeployment);
            writeAtomically(rendered.checksumsFile(), checksums);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not render bundle instance artifacts", exception);
        }
        return rendered;
    }

    private static String envContent(BundleInstanceManifest manifest, String manifestHash, String launchNonce) {
        return envLine("FULCRUM_BUNDLE_ID", manifest.bundleId())
                + envLine("FULCRUM_BUNDLE_DIGEST", manifest.bundleDigest())
                + envLine("FULCRUM_ARTIFACT_REF", manifest.artifactRef())
                + envLine("FULCRUM_BACKEND_IMAGE_REF", manifest.backendImageRef())
                + envLine("FULCRUM_BACKEND_IMAGE_DIGEST", manifest.backendImageDigest())
                + envLine("FULCRUM_INSTANCE_ID", manifest.instanceId())
                + envLine("FULCRUM_INSTANCE_KIND", manifest.instanceKind())
                + envLine("FULCRUM_POOL_ID", manifest.poolId())
                + envLine("FULCRUM_MACHINE_REF", manifest.machineRef())
                + envLine("FULCRUM_PRINCIPAL_ID", manifest.principalId())
                + envLine("FULCRUM_CREDENTIAL_REF", manifest.credentialRef())
                + envLine("FULCRUM_GRANT_FINGERPRINT", manifest.grantFingerprint())
                + envLine("FULCRUM_MANIFEST_HASH", manifestHash)
                + envLine("FULCRUM_LAUNCH_NONCE", launchNonce)
                + envLine("FULCRUM_BOOT_NONCE", launchNonce)
                + envLine("FULCRUM_AUTHORITY_DOMAINS", commaSeparated(manifest.authorityDomains()))
                + envLine("FULCRUM_RESOURCE_CLASSES", commaSeparated(manifest.resourceClasses()))
                + envLine("FULCRUM_REGISTRATION_ENDPOINT", manifest.registrationEndpoint())
                + envLine("FULCRUM_AUTHORITY_REGISTRATION_ENDPOINT", manifest.registrationEndpoint())
                + envLine("FULCRUM_READY_FILE", "/var/run/fulcrum/backend.ready")
                + envLine("FULCRUM_STARTUP_MODE", "serve")
                + envLine("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "fulcrum-kafka:9092")
                + envLine("FULCRUM_POSTGRES_JDBC_URL", "jdbc:postgresql://fulcrum-postgres:5432/fulcrum")
                + envLine("FULCRUM_POSTGRES_USERNAME", "fulcrum")
                + envLine("FULCRUM_POSTGRES_PASSWORD", "fulcrum-dev-password")
                + envLine("FULCRUM_CASSANDRA_CONTACT_POINTS", "fulcrum-cassandra:9042")
                + envLine("FULCRUM_CASSANDRA_LOCAL_DATACENTER", "datacenter1")
                + envLine("FULCRUM_VALKEY_ENDPOINT", "fulcrum-valkey:6379");
    }

    private static String composeContent(BundleInstanceManifest manifest, String manifestHash) {
        String projectName = "fulcrum-" + safeName(manifest.instanceId()) + "-" + manifestHash.substring(0, 12);
        return "name: " + yamlQuote(projectName) + System.lineSeparator()
                + System.lineSeparator()
                + "services:" + System.lineSeparator()
                + "  backend:" + System.lineSeparator()
                + "    image: " + yamlQuote(manifest.backendImageRef()) + System.lineSeparator()
                + "    restart: unless-stopped" + System.lineSeparator()
                + "    env_file:" + System.lineSeparator()
                + "      - ./env/bootstrap.env" + System.lineSeparator()
                + "    volumes:" + System.lineSeparator()
                + "      - ./runtime:/var/run/fulcrum" + System.lineSeparator()
                + "    labels:" + System.lineSeparator()
                + "      sh.harold.fulcrum.bundle-id: " + yamlQuote(manifest.bundleId()) + System.lineSeparator()
                + "      sh.harold.fulcrum.instance-id: " + yamlQuote(manifest.instanceId()) + System.lineSeparator()
                + "      sh.harold.fulcrum.manifest-hash: " + yamlQuote(manifestHash) + System.lineSeparator()
                + "      sh.harold.fulcrum.backend-image-digest: " + yamlQuote(manifest.backendImageDigest()) + System.lineSeparator()
                + "    networks:" + System.lineSeparator()
                + "      - fulcrum-runtime" + System.lineSeparator()
                + System.lineSeparator()
                + "networks:" + System.lineSeparator()
                + "  fulcrum-runtime:" + System.lineSeparator()
                + "    name: fulcrum-single-machine_default" + System.lineSeparator()
                + "    external: true" + System.lineSeparator();
    }

    private static String helmChartContent(BundleInstanceManifest manifest) {
        String chartName = "fulcrum-bundle-" + safeKubernetesName(manifest.bundleId(), manifest.manifestHash());
        return "apiVersion: v2" + System.lineSeparator()
                + "name: " + yamlQuote(chartName) + System.lineSeparator()
                + "description: " + yamlQuote("Fulcrum bundle backend " + manifest.bundleId()) + System.lineSeparator()
                + "type: application" + System.lineSeparator()
                + "version: 0.1.0" + System.lineSeparator()
                + "appVersion: " + yamlQuote(manifest.backendImageDigest()) + System.lineSeparator();
    }

    private static String helmValuesContent(BundleInstanceManifest manifest, String manifestHash) {
        return "bundleId: " + yamlQuote(manifest.bundleId()) + System.lineSeparator()
                + "instanceId: " + yamlQuote(manifest.instanceId()) + System.lineSeparator()
                + "placementProfile: " + yamlQuote(manifest.placementProfile()) + System.lineSeparator()
                + "manifestHash: " + yamlQuote(manifestHash) + System.lineSeparator();
    }

    private static String helmDeploymentContent(
            BundleInstanceManifest manifest,
            String manifestHash,
            String launchNonce) {
        String deploymentName = safeKubernetesName(manifest.instanceId(), manifestHash);
        String selector = "fulcrum-bundle-" + deploymentName;
        return "apiVersion: apps/v1" + System.lineSeparator()
                + "kind: Deployment" + System.lineSeparator()
                + "metadata:" + System.lineSeparator()
                + "  name: " + yamlQuote(deploymentName) + System.lineSeparator()
                + "  labels:" + System.lineSeparator()
                + "    app.kubernetes.io/name: \"fulcrum-bundle-backend\"" + System.lineSeparator()
                + "    app.kubernetes.io/instance: " + yamlQuote(deploymentName) + System.lineSeparator()
                + "    app.kubernetes.io/managed-by: \"Helm\"" + System.lineSeparator()
                + "    sh.harold.fulcrum/bundle-id: " + yamlQuote(manifest.bundleId()) + System.lineSeparator()
                + "    sh.harold.fulcrum/instance-id: " + yamlQuote(manifest.instanceId()) + System.lineSeparator()
                + "    sh.harold.fulcrum/profile: " + yamlQuote(manifest.placementProfile()) + System.lineSeparator()
                + "  annotations:" + System.lineSeparator()
                + "    meta.helm.sh/release-name: " + yamlQuote(deploymentName) + System.lineSeparator()
                + "    meta.helm.sh/release-namespace: \"fulcrum\"" + System.lineSeparator()
                + "spec:" + System.lineSeparator()
                + "  replicas: 1" + System.lineSeparator()
                + "  selector:" + System.lineSeparator()
                + "    matchLabels:" + System.lineSeparator()
                + "      sh.harold.fulcrum/instance-selector: " + yamlQuote(selector) + System.lineSeparator()
                + "  template:" + System.lineSeparator()
                + "    metadata:" + System.lineSeparator()
                + "      labels:" + System.lineSeparator()
                + "        sh.harold.fulcrum/instance-selector: " + yamlQuote(selector) + System.lineSeparator()
                + "        sh.harold.fulcrum/bundle-id: " + yamlQuote(manifest.bundleId()) + System.lineSeparator()
                + "        sh.harold.fulcrum/instance-id: " + yamlQuote(manifest.instanceId()) + System.lineSeparator()
                + "    spec:" + System.lineSeparator()
                + "      containers:" + System.lineSeparator()
                + "        - name: backend" + System.lineSeparator()
                + "          image: " + yamlQuote(manifest.backendImageRef()) + System.lineSeparator()
                + "          imagePullPolicy: IfNotPresent" + System.lineSeparator()
                + "          env:" + System.lineSeparator()
                + helmEnv("FULCRUM_BUNDLE_ID", manifest.bundleId())
                + helmEnv("FULCRUM_BUNDLE_DIGEST", manifest.bundleDigest())
                + helmEnv("FULCRUM_ARTIFACT_REF", manifest.artifactRef())
                + helmEnv("FULCRUM_BACKEND_IMAGE_REF", manifest.backendImageRef())
                + helmEnv("FULCRUM_BACKEND_IMAGE_DIGEST", manifest.backendImageDigest())
                + helmEnv("FULCRUM_INSTANCE_ID", manifest.instanceId())
                + helmEnv("FULCRUM_INSTANCE_KIND", manifest.instanceKind())
                + helmEnv("FULCRUM_POOL_ID", manifest.poolId())
                + helmEnv("FULCRUM_MACHINE_REF", manifest.machineRef())
                + helmEnv("FULCRUM_PRINCIPAL_ID", manifest.principalId())
                + helmEnv("FULCRUM_CREDENTIAL_REF", manifest.credentialRef())
                + helmEnv("FULCRUM_GRANT_FINGERPRINT", manifest.grantFingerprint())
                + helmEnv("FULCRUM_MANIFEST_HASH", manifestHash)
                + helmEnv("FULCRUM_LAUNCH_NONCE", launchNonce)
                + helmEnv("FULCRUM_BOOT_NONCE", launchNonce)
                + helmEnv("FULCRUM_AUTHORITY_DOMAINS", commaSeparated(manifest.authorityDomains()))
                + helmEnv("FULCRUM_RESOURCE_CLASSES", commaSeparated(manifest.resourceClasses()))
                + helmEnv("FULCRUM_REGISTRATION_ENDPOINT", manifest.registrationEndpoint())
                + helmEnv("FULCRUM_AUTHORITY_REGISTRATION_ENDPOINT", manifest.registrationEndpoint())
                + helmEnv("FULCRUM_READY_FILE", "/var/run/fulcrum/backend.ready")
                + helmEnv("FULCRUM_STARTUP_MODE", "serve")
                + helmEnv("FULCRUM_KAFKA_BOOTSTRAP_SERVERS", "fulcrum-kafka:9092")
                + helmEnv("FULCRUM_POSTGRES_JDBC_URL", "jdbc:postgresql://fulcrum-postgres:5432/fulcrum")
                + helmEnv("FULCRUM_POSTGRES_USERNAME", "fulcrum")
                + helmEnv("FULCRUM_POSTGRES_PASSWORD", "fulcrum-dev-password")
                + helmEnv("FULCRUM_CASSANDRA_CONTACT_POINTS", "fulcrum-cassandra:9042")
                + helmEnv("FULCRUM_CASSANDRA_LOCAL_DATACENTER", "datacenter1")
                + helmEnv("FULCRUM_VALKEY_ENDPOINT", "fulcrum-valkey:6379");
    }

    private static String checksumsContent(
            String manifestHash,
            String manifestJson,
            String env,
            String compose,
            String helmChart,
            String helmValues,
            String helmDeployment) {
        return "manifestHash=" + manifestHash + System.lineSeparator()
                + "manifest.json=" + AuthorityBackendDescriptorDigests.sha256Hex(manifestJson) + System.lineSeparator()
                + "env/bootstrap.env=" + AuthorityBackendDescriptorDigests.sha256Hex(env) + System.lineSeparator()
                + "compose.yaml=" + AuthorityBackendDescriptorDigests.sha256Hex(compose) + System.lineSeparator()
                + "helm/Chart.yaml=" + AuthorityBackendDescriptorDigests.sha256Hex(helmChart) + System.lineSeparator()
                + "helm/values.yaml=" + AuthorityBackendDescriptorDigests.sha256Hex(helmValues) + System.lineSeparator()
                + "helm/templates/backend-deployment.yaml="
                + AuthorityBackendDescriptorDigests.sha256Hex(helmDeployment) + System.lineSeparator();
    }

    private static String envLine(String key, String value) {
        return key + "=" + envValue(value) + System.lineSeparator();
    }

    private static String envValue(String value) {
        String checked = Objects.requireNonNull(value, "value");
        if (checked.contains("\n") || checked.contains("\r")) {
            throw new IllegalArgumentException("env values must be single-line");
        }
        return checked;
    }

    private static String yamlQuote(String value) {
        return "\"" + envValue(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String helmEnv(String key, String value) {
        return "            - name: " + envValue(key) + System.lineSeparator()
                + "              value: " + yamlQuote(value) + System.lineSeparator();
    }

    private static String commaSeparated(java.util.List<String> values) {
        return values.stream()
                .map(BundleInstanceArtifactRenderer::envValue)
                .collect(Collectors.joining(","));
    }

    private static String safeName(String value) {
        String safe = envValue(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("rendered instance name must contain at least one safe character");
        }
        return safe;
    }

    private static String safeKubernetesName(String value, String manifestHash) {
        String safe = safeName(value);
        if (safe.length() <= 50) {
            return safe;
        }
        return safe.substring(0, 50).replaceAll("-+$", "") + "-" + manifestHash.substring(0, 12);
    }

    private static void writeAtomically(Path path, String content) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        Files.writeString(temporary, content);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
