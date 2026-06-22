package sh.harold.fulcrum.distribution.launcher;

import java.nio.file.Path;
import java.util.List;

interface BundleRuntimeCommandRunner {
    BundleRuntimeCommandResult run(List<String> command, Path workingDirectory);
}
