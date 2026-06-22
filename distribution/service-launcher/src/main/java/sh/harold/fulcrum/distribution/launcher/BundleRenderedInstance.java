package sh.harold.fulcrum.distribution.launcher;

import java.nio.file.Path;
import java.util.Objects;

record BundleRenderedInstance(
        Path workDir,
        Path manifestFile,
        Path envFile,
        Path composeFile,
        Path helmChartDir,
        Path helmDeploymentFile,
        Path checksumsFile,
        String manifestHash) {
    BundleRenderedInstance {
        workDir = Objects.requireNonNull(workDir, "workDir");
        manifestFile = Objects.requireNonNull(manifestFile, "manifestFile");
        envFile = Objects.requireNonNull(envFile, "envFile");
        composeFile = Objects.requireNonNull(composeFile, "composeFile");
        helmChartDir = Objects.requireNonNull(helmChartDir, "helmChartDir");
        helmDeploymentFile = Objects.requireNonNull(helmDeploymentFile, "helmDeploymentFile");
        checksumsFile = Objects.requireNonNull(checksumsFile, "checksumsFile");
        manifestHash = requireNonBlank(manifestHash, "manifestHash");
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
