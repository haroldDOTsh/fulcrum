package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperGameserverImageLayoutTest {
    @Test
    void serviceLauncherImageContextInstallsFulcrumDistribution() throws IOException {
        String dockerfile = resource("fulcrum/container/service-launcher/Dockerfile");
        String readme = resource("fulcrum/container/service-launcher/README.md");

        assertTrue(dockerfile.contains("FROM eclipse-temurin:26-jre"));
        assertTrue(dockerfile.contains("COPY fulcrum /opt/fulcrum/fulcrum"));
        assertTrue(dockerfile.contains("RUN chmod +x /opt/fulcrum/fulcrum/bin/fulcrum"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"/opt/fulcrum/fulcrum/bin/fulcrum\"]"));

        assertTrue(readme.contains("serviceLauncherImageContext"));
        assertTrue(readme.contains("serviceLauncherImage"));
        assertTrue(readme.contains("fulcrum.serviceLauncherImage"));
        assertTrue(readme.contains("LobbyWorldArtifactProvisioner"));
    }

    @Test
    void paperGameserverImageContextInstallsLauncherPluginAndEntrypoint() throws IOException {
        String dockerfile = resource("fulcrum/container/paper-gameserver/Dockerfile");
        String entrypoint = resource("fulcrum/container/paper-gameserver/entrypoint.sh");
        String readme = resource("fulcrum/container/paper-gameserver/README.md");
        Properties paperServer = properties("fulcrum/container/paper-gameserver/paper-server.lock");

        assertTrue(dockerfile.contains("FROM eclipse-temurin:26-jre"));
        assertTrue(dockerfile.contains("COPY fulcrum /opt/fulcrum/fulcrum"));
        assertTrue(dockerfile.contains("COPY plugins/FulcrumPaperAgent.jar /opt/fulcrum/paper/plugins/FulcrumPaperAgent.jar"));
        assertTrue(dockerfile.contains("ARG PAPER_SERVER_URL=\"" + paperServer.getProperty("downloadUrl") + "\""));
        assertTrue(dockerfile.contains("ARG PAPER_SERVER_SHA256=\"" + paperServer.getProperty("sha256") + "\""));
        assertTrue(dockerfile.contains("ARG PAPER_DOWNLOAD_USER_AGENT=\"fulcrum-paper-gameserver/1.0"));
        assertTrue(dockerfile.contains("-H \"User-Agent: ${PAPER_DOWNLOAD_USER_AGENT}\""));
        assertTrue(dockerfile.contains("sha256sum -c -"));
        assertTrue(dockerfile.contains("-o /opt/fulcrum/paper/paper-server.jar"));
        assertTrue(dockerfile.contains("EXPOSE 25565 18081"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"/opt/fulcrum/bin/paper-gameserver\"]"));

        assertTrue(entrypoint.contains("/opt/fulcrum/fulcrum/bin/fulcrum"));
        assertTrue(entrypoint.contains(": \"${FULCRUM_PROBE_PORT:=18081}\""));
        assertTrue(entrypoint.contains("if [ \"$#\" -eq 0 ]; then"));
        assertTrue(entrypoint.contains("--role=paper-agent"));
        assertTrue(entrypoint.contains("--probe-port=\"${FULCRUM_PROBE_PORT}\""));
        assertTrue(entrypoint.contains("/opt/fulcrum/fulcrum/bin/fulcrum \"$@\""));
        assertTrue(entrypoint.contains("exec java ${PAPER_JAVA_OPTS} -jar \"${PAPER_SERVER_JAR}\" ${PAPER_ARGS}"));
        assertTrue(entrypoint.contains("MINECRAFT_EULA"));
        assertTrue(entrypoint.contains("cat > server.properties"));
        assertTrue(entrypoint.contains("server-port=${PAPER_SERVER_PORT}"));
        assertTrue(entrypoint.contains("online-mode=${PAPER_ONLINE_MODE}"));
        assertTrue(entrypoint.contains("enforce-secure-profile=${PAPER_ENFORCE_SECURE_PROFILE}"));
        assertTrue(entrypoint.contains("prevent-proxy-connections=false"));
        assertTrue(entrypoint.contains("cat > bukkit.yml"));
        assertTrue(entrypoint.contains("connection-throttle: -1"));

        assertTrue(readme.contains("paperGameserverImageContext"));
        assertTrue(readme.contains("paperGameserverImage"));
        assertTrue(readme.contains("fulcrum.paperGameserverImage"));
        assertTrue(readme.contains("paper-server.lock"));
        assertTrue(readme.contains("PaperMC's downloads service"));
        assertTrue(readme.contains("stable Paper build"));
        assertTrue(readme.contains("FULCRUM_PAPER_OBSERVATION_BRIDGE_URL"));
        assertTrue(readme.contains("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL"));
        assertTrue(readme.contains("FULCRUM_PAPER_REWARD_BRIDGE_URL"));
        assertTrue(readme.contains("online-mode=false"));
        assertTrue(readme.contains("connection-throttle=-1"));

        assertEquals("paper-26.1.2-70.jar", paperServer.getProperty("downloadName"));
        assertEquals("STABLE", paperServer.getProperty("channel"));
        assertEquals("775", paperServer.getProperty("protocolVersion"));
        assertTrue(paperServer.getProperty("sha256").matches("[a-f0-9]{64}"));
    }

    @Test
    void hostPluginJarsIncludeRuntimeClasspathForServerClassloaders() throws IOException {
        String paperBuild = Files.readString(Path.of("..", "..", "host", "paper-agent", "build.gradle.kts"));
        String velocityBuild = Files.readString(Path.of("..", "..", "host", "velocity-agent", "build.gradle.kts"));

        assertTrue(paperBuild.contains("tasks.named<Jar>(\"jar\")"));
        assertTrue(paperBuild.contains("configurations.runtimeClasspath.get()"));
        assertTrue(paperBuild.contains("map { zipTree(it) }"));
        assertTrue(velocityBuild.contains("tasks.named<Jar>(\"jar\")"));
        assertTrue(velocityBuild.contains("configurations.runtimeClasspath.get()"));
        assertTrue(velocityBuild.contains("map { zipTree(it) }"));
    }

    @Test
    void velocityProxyImageContextInstallsLauncherPluginAndEntrypoint() throws IOException {
        String dockerfile = resource("fulcrum/container/velocity-proxy/Dockerfile");
        String entrypoint = resource("fulcrum/container/velocity-proxy/entrypoint.sh");
        String configuration = resource("fulcrum/container/velocity-proxy/velocity.toml");
        String readme = resource("fulcrum/container/velocity-proxy/README.md");
        Properties velocityServer = properties("fulcrum/container/velocity-proxy/velocity-server.lock");

        assertTrue(dockerfile.contains("FROM eclipse-temurin:26-jre"));
        assertTrue(dockerfile.contains("COPY fulcrum /opt/fulcrum/fulcrum"));
        assertTrue(dockerfile.contains("COPY plugins/FulcrumVelocityAgent.jar /opt/fulcrum/velocity/plugins/FulcrumVelocityAgent.jar"));
        assertTrue(dockerfile.contains("ARG VELOCITY_SERVER_URL=\"" + velocityServer.getProperty("downloadUrl") + "\""));
        assertTrue(dockerfile.contains("ARG VELOCITY_SERVER_SHA256=\"" + velocityServer.getProperty("sha256") + "\""));
        assertTrue(dockerfile.contains("ARG VELOCITY_DOWNLOAD_USER_AGENT=\"fulcrum-velocity-proxy/1.0"));
        assertTrue(dockerfile.contains("-H \"User-Agent: ${VELOCITY_DOWNLOAD_USER_AGENT}\""));
        assertTrue(dockerfile.contains("sha256sum -c -"));
        assertTrue(dockerfile.contains("-o /opt/fulcrum/velocity/velocity-server.jar"));
        assertTrue(dockerfile.contains("EXPOSE 25565 8080"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"/opt/fulcrum/bin/velocity-proxy\"]"));

        assertTrue(entrypoint.contains("/opt/fulcrum/fulcrum/bin/fulcrum"));
        assertTrue(entrypoint.contains("--role=velocity-agent"));
        assertTrue(entrypoint.contains("exec java ${VELOCITY_JAVA_OPTS} -jar \"${VELOCITY_SERVER_JAR}\" ${VELOCITY_ARGS}"));

        assertTrue(configuration.contains("bind = \"0.0.0.0:25565\""));
        assertTrue(configuration.contains("online-mode = false"));
        assertTrue(configuration.contains("player-info-forwarding-mode = \"none\""));

        assertTrue(readme.contains("velocityProxyImageContext"));
        assertTrue(readme.contains("velocityProxyImage"));
        assertTrue(readme.contains("fulcrum.velocityProxyImage"));
        assertTrue(readme.contains("velocity-server.lock"));

        assertEquals("velocity-3.5.0-SNAPSHOT-605.jar", velocityServer.getProperty("downloadName"));
        assertEquals("STABLE", velocityServer.getProperty("channel"));
        assertEquals("3.5.0-SNAPSHOT", velocityServer.getProperty("apiVersion"));
        assertTrue(velocityServer.getProperty("sha256").matches("[a-f0-9]{64}"));
    }

    private static String resource(String name) throws IOException {
        try (var stream = PaperGameserverImageLayoutTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Properties properties(String name) throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(resource(name).getBytes(StandardCharsets.UTF_8)));
        return properties;
    }
}
