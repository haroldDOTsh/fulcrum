package sh.harold.fulcrum.api.messagebus.messages;

import java.io.Serializable;

/**
 * Sanitized startup proof that a runtime node is using the remote Data Authority contract.
 */
public class RuntimeDataAuthorityAttestation implements Serializable {
    private static final long serialVersionUID = 1L;

    private String nodeKind;
    private int manifestVersion;
    private boolean passed;
    private String runtimeDataMode;
    private String cacheMode;
    private int commandSchemaVersion;
    private String commandContractFingerprint;
    private int readSchemaVersion;
    private String readContractFingerprint;
    private String configFingerprint;
    private String classpathFingerprint;
    private String attestationFingerprint;

    public RuntimeDataAuthorityAttestation() {
        // Default constructor for serialization
    }

    public RuntimeDataAuthorityAttestation(
        String nodeKind,
        int manifestVersion,
        boolean passed,
        String runtimeDataMode,
        String cacheMode,
        int commandSchemaVersion,
        String commandContractFingerprint,
        int readSchemaVersion,
        String readContractFingerprint,
        String configFingerprint,
        String classpathFingerprint,
        String attestationFingerprint
    ) {
        this.nodeKind = nodeKind;
        this.manifestVersion = manifestVersion;
        this.passed = passed;
        this.runtimeDataMode = runtimeDataMode;
        this.cacheMode = cacheMode;
        this.commandSchemaVersion = commandSchemaVersion;
        this.commandContractFingerprint = commandContractFingerprint;
        this.readSchemaVersion = readSchemaVersion;
        this.readContractFingerprint = readContractFingerprint;
        this.configFingerprint = configFingerprint;
        this.classpathFingerprint = classpathFingerprint;
        this.attestationFingerprint = attestationFingerprint;
    }

    public String getNodeKind() {
        return nodeKind;
    }

    public void setNodeKind(String nodeKind) {
        this.nodeKind = nodeKind;
    }

    public int getManifestVersion() {
        return manifestVersion;
    }

    public void setManifestVersion(int manifestVersion) {
        this.manifestVersion = manifestVersion;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getRuntimeDataMode() {
        return runtimeDataMode;
    }

    public void setRuntimeDataMode(String runtimeDataMode) {
        this.runtimeDataMode = runtimeDataMode;
    }

    public String getCacheMode() {
        return cacheMode;
    }

    public void setCacheMode(String cacheMode) {
        this.cacheMode = cacheMode;
    }

    public int getCommandSchemaVersion() {
        return commandSchemaVersion;
    }

    public void setCommandSchemaVersion(int commandSchemaVersion) {
        this.commandSchemaVersion = commandSchemaVersion;
    }

    public String getCommandContractFingerprint() {
        return commandContractFingerprint;
    }

    public void setCommandContractFingerprint(String commandContractFingerprint) {
        this.commandContractFingerprint = commandContractFingerprint;
    }

    public int getReadSchemaVersion() {
        return readSchemaVersion;
    }

    public void setReadSchemaVersion(int readSchemaVersion) {
        this.readSchemaVersion = readSchemaVersion;
    }

    public String getReadContractFingerprint() {
        return readContractFingerprint;
    }

    public void setReadContractFingerprint(String readContractFingerprint) {
        this.readContractFingerprint = readContractFingerprint;
    }

    public String getConfigFingerprint() {
        return configFingerprint;
    }

    public void setConfigFingerprint(String configFingerprint) {
        this.configFingerprint = configFingerprint;
    }

    public String getClasspathFingerprint() {
        return classpathFingerprint;
    }

    public void setClasspathFingerprint(String classpathFingerprint) {
        this.classpathFingerprint = classpathFingerprint;
    }

    public String getAttestationFingerprint() {
        return attestationFingerprint;
    }

    public void setAttestationFingerprint(String attestationFingerprint) {
        this.attestationFingerprint = attestationFingerprint;
    }

    public String summary() {
        return "nodeKind=" + nodeKind
            + ", manifestVersion=" + manifestVersion
            + ", passed=" + passed
            + ", runtimeDataMode=" + runtimeDataMode
            + ", cacheMode=" + cacheMode
            + ", commandContract=" + shortFingerprint(commandContractFingerprint)
            + ", readContract=" + shortFingerprint(readContractFingerprint)
            + ", configFingerprint=" + configFingerprint
            + ", classpathFingerprint=" + classpathFingerprint
            + ", attestationFingerprint=" + attestationFingerprint;
    }

    @Override
    public String toString() {
        return "RuntimeDataAuthorityAttestation[" + summary() + "]";
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }
}
