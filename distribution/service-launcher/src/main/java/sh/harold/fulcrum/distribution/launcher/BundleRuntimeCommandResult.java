package sh.harold.fulcrum.distribution.launcher;

record BundleRuntimeCommandResult(
        int exitCode,
        String output) {
    BundleRuntimeCommandResult {
        output = output == null ? "" : output;
    }

    boolean succeeded() {
        return exitCode == 0;
    }
}
