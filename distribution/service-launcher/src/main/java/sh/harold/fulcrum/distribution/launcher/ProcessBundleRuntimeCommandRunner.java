package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class ProcessBundleRuntimeCommandRunner implements BundleRuntimeCommandRunner {
    @Override
    public BundleRuntimeCommandResult run(List<String> command, Path workingDirectory) {
        List<String> checkedCommand = List.copyOf(Objects.requireNonNull(command, "command"));
        if (checkedCommand.isEmpty()) {
            throw new IllegalArgumentException("runtime command must not be empty");
        }
        Path checkedWorkingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        ProcessBuilder builder = new ProcessBuilder(checkedCommand)
                .directory(checkedWorkingDirectory.toFile())
                .redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new BundleRuntimeCommandResult(exitCode, output);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start runtime command", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Runtime command interrupted", exception);
        }
    }
}
