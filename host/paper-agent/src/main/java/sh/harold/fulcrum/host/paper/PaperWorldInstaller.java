package sh.harold.fulcrum.host.paper;

import java.io.IOException;

@FunctionalInterface
public interface PaperWorldInstaller {
    PaperPreparedWorld install(CachedArtifact artifact, PaperGameServerAssignment assignment) throws IOException;
}
