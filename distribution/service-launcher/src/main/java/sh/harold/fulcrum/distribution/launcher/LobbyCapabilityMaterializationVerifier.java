package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.store.valkey.ValkeyClientHandle;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.punishment.PunishmentState;
import sh.harold.fulcrum.standard.rank.RankAuthority;
import sh.harold.fulcrum.standard.rank.RankState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LobbyCapabilityMaterializationVerifier {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    private LobbyCapabilityMaterializationVerifier() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 0) {
            throw new IllegalArgumentException("LobbyCapabilityMaterializationVerifier reads configuration from environment");
        }
        Config config = Config.fromEnvironment(RuntimeEnvironment.system());
        try (ValkeyClientHandle valkey = ValkeyClientHandle.create(
                config.valkeyEndpoint().host(),
                config.valkeyEndpoint().port())) {
            Result result = verify(config, valkey, Clock.systemUTC());
            System.out.println("materializedCapabilitySubjects=" + result.subjectCount());
            System.out.println("materializedCapabilityChecks=" + result.checkCount());
        }
    }

    static Result verify(Config config, ValkeyClientHandle valkey, Clock clock) throws InterruptedException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(valkey, "valkey");
        Objects.requireNonNull(clock, "clock");
        Instant deadline = clock.instant().plus(config.timeout());
        List<String> missing = missingRequirements(config, valkey);
        while (!missing.isEmpty() && clock.instant().isBefore(deadline)) {
            Thread.sleep(Math.max(1L, config.pollInterval().toMillis()));
            missing = missingRequirements(config, valkey);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("lobby capability materialization did not complete within "
                    + config.timeout() + ": " + String.join("; ", missing));
        }
        return new Result(config.subjects().size(), config.checkCount());
    }

    static List<String> missingRequirements(Config config, ValkeyClientHandle valkey) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(valkey, "valkey");
        List<String> missing = new ArrayList<>();
        for (ExpectedSubject subject : config.subjects()) {
            checkProfile(subject, valkey, missing);
            checkRank(subject, valkey, missing);
            subject.expectedPunishmentReason()
                    .ifPresent(reason -> checkPunishment(subject, reason, valkey, missing));
        }
        return List.copyOf(missing);
    }

    static ExpectedPayloads expectedPayloads(Config config) {
        Objects.requireNonNull(config, "config");
        List<String> cacheKeys = new ArrayList<>();
        for (ExpectedSubject subject : config.subjects()) {
            cacheKeys.add(PlayerProfileAuthority.cacheKey(subject.subjectId()));
            cacheKeys.add(RankAuthority.cacheKey(subject.subjectId()));
            if (subject.expectedPunishmentReason().isPresent()) {
                cacheKeys.add(PunishmentAuthority.cacheKey(subject.subjectId()));
            }
        }
        return new ExpectedPayloads(cacheKeys);
    }

    private static void checkProfile(ExpectedSubject subject, ValkeyClientHandle valkey, List<String> missing) {
        String cacheKey = PlayerProfileAuthority.cacheKey(subject.subjectId());
        String payload = valkey.client().get(cacheKey);
        if (payload == null || payload.isBlank()) {
            missing.add(cacheKey + " missing");
            return;
        }
        PlayerProfileState state = PlayerProfileState.parse(payload);
        if (state.current().isEmpty()) {
            missing.add(cacheKey + " empty");
            return;
        }
        var snapshot = state.current().orElseThrow();
        if (!snapshot.subjectId().equals(subject.subjectId())) {
            missing.add(cacheKey + " subject mismatch");
        }
        if (!snapshot.displayName().equals(subject.expectedDisplayName())) {
            missing.add(cacheKey + " displayName mismatch");
        }
    }

    private static void checkRank(ExpectedSubject subject, ValkeyClientHandle valkey, List<String> missing) {
        String cacheKey = RankAuthority.cacheKey(subject.subjectId());
        String payload = valkey.client().get(cacheKey);
        if (payload == null || payload.isBlank()) {
            missing.add(cacheKey + " missing");
            return;
        }
        RankState state = RankState.parse(payload);
        if (state.current().isEmpty()) {
            missing.add(cacheKey + " empty");
            return;
        }
        var snapshot = state.current().orElseThrow();
        if (!snapshot.subjectId().equals(subject.subjectId())) {
            missing.add(cacheKey + " subject mismatch");
        }
        if (!snapshot.primaryRankKey().equals(subject.expectedRankKey())) {
            missing.add(cacheKey + " primaryRankKey mismatch");
        }
    }

    private static void checkPunishment(
            ExpectedSubject subject,
            String expectedReason,
            ValkeyClientHandle valkey,
            List<String> missing) {
        String cacheKey = PunishmentAuthority.cacheKey(subject.subjectId());
        String payload = valkey.client().get(cacheKey);
        if (payload == null || payload.isBlank()) {
            missing.add(cacheKey + " missing");
            return;
        }
        PunishmentState state = PunishmentState.parse(payload);
        if (state.active().isEmpty()) {
            missing.add(cacheKey + " empty");
            return;
        }
        var snapshot = state.active().orElseThrow();
        if (!snapshot.subjectId().equals(subject.subjectId())) {
            missing.add(cacheKey + " subject mismatch");
        }
        if (!snapshot.reason().equals(expectedReason)) {
            missing.add(cacheKey + " reason mismatch");
        }
    }

    record Config(
            HostPort valkeyEndpoint,
            Duration timeout,
            Duration pollInterval,
            List<ExpectedSubject> subjects) {
        Config {
            valkeyEndpoint = Objects.requireNonNull(valkeyEndpoint, "valkeyEndpoint");
            timeout = Objects.requireNonNull(timeout, "timeout");
            pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (pollInterval.isZero() || pollInterval.isNegative()) {
                throw new IllegalArgumentException("pollInterval must be positive");
            }
            subjects = List.copyOf(Objects.requireNonNull(subjects, "subjects"));
            if (subjects.isEmpty()) {
                throw new IllegalArgumentException("subjects must not be empty");
            }
        }

        static Config fromEnvironment(RuntimeEnvironment environment) {
            Objects.requireNonNull(environment, "environment");
            List<ExpectedSubject> subjects = new ArrayList<>();
            subjects.add(acceptedSubject(environment));
            subjects.add(secondAcceptedSubject(environment));
            subjects.add(scaleOutAcceptedSubject(environment));
            deniedSubject(environment).ifPresent(subjects::add);
            return new Config(
                    HostPort.parse(required(environment, "FULCRUM_VALKEY_ENDPOINT")),
                    environment.value("FULCRUM_LOBBY_CAPABILITY_MATERIALIZATION_TIMEOUT")
                            .map(Duration::parse)
                            .orElse(DEFAULT_TIMEOUT),
                    environment.value("FULCRUM_LOBBY_CAPABILITY_MATERIALIZATION_POLL_INTERVAL")
                            .map(Duration::parse)
                            .orElse(DEFAULT_POLL_INTERVAL),
                    subjects);
        }

        int checkCount() {
            return subjects.stream()
                    .mapToInt(subject -> 2 + (subject.expectedPunishmentReason().isPresent() ? 1 : 0))
                    .sum();
        }

        private static ExpectedSubject acceptedSubject(RuntimeEnvironment environment) {
            String username = environment.value("FULCRUM_LOBBY_ACCEPTED_USERNAME")
                    .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_ACCEPTED_USERNAME);
            return new ExpectedSubject(
                    subjectId(environment, "FULCRUM_LOBBY_ACCEPTED_SUBJECT_ID", username),
                    environment.value("FULCRUM_LOBBY_ACCEPTED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_ACCEPTED_RANK_KEY")
                            .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_ACCEPTED_RANK_KEY),
                    Optional.empty());
        }

        private static ExpectedSubject secondAcceptedSubject(RuntimeEnvironment environment) {
            String username = environment.value("FULCRUM_LOBBY_SECOND_ACCEPTED_USERNAME")
                    .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_SECOND_ACCEPTED_USERNAME);
            return new ExpectedSubject(
                    subjectId(environment, "FULCRUM_LOBBY_SECOND_ACCEPTED_SUBJECT_ID", username),
                    environment.value("FULCRUM_LOBBY_SECOND_ACCEPTED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_SECOND_ACCEPTED_RANK_KEY")
                            .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_ACCEPTED_RANK_KEY),
                    Optional.empty());
        }

        private static ExpectedSubject scaleOutAcceptedSubject(RuntimeEnvironment environment) {
            String username = environment.value("FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_USERNAME")
                    .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_SCALE_OUT_ACCEPTED_USERNAME);
            return new ExpectedSubject(
                    subjectId(environment, "FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_SUBJECT_ID", username),
                    environment.value("FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_RANK_KEY")
                            .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_ACCEPTED_RANK_KEY),
                    Optional.empty());
        }

        private static Optional<ExpectedSubject> deniedSubject(RuntimeEnvironment environment) {
            Optional<String> deniedUsername = environment.value("FULCRUM_LOBBY_DENIED_USERNAME");
            Optional<String> deniedSubjectId = environment.value("FULCRUM_LOBBY_DENIED_SUBJECT_ID");
            if (deniedUsername.isEmpty() && deniedSubjectId.isEmpty()) {
                return Optional.empty();
            }
            String username = deniedUsername.orElse("DeniedSubject");
            return Optional.of(new ExpectedSubject(
                    deniedSubjectId
                            .map(value -> new SubjectId(UUID.fromString(value)))
                            .orElseGet(() -> LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username)),
                    environment.value("FULCRUM_LOBBY_DENIED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_DENIED_RANK_KEY")
                            .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_DENIED_RANK_KEY),
                    Optional.of(environment.value("FULCRUM_LOBBY_DENIED_PUNISHMENT_REASON")
                            .orElse(LobbyCapabilitySeedProvisioner.DEFAULT_DENIED_PUNISHMENT_REASON))));
        }

        private static SubjectId subjectId(RuntimeEnvironment environment, String envName, String username) {
            return environment.value(envName)
                    .map(value -> new SubjectId(UUID.fromString(value)))
                    .orElseGet(() -> LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username));
        }
    }

    record ExpectedSubject(
            SubjectId subjectId,
            String expectedDisplayName,
            String expectedRankKey,
            Optional<String> expectedPunishmentReason) {
        ExpectedSubject {
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            expectedDisplayName = requireNonBlank(expectedDisplayName, "expectedDisplayName");
            expectedRankKey = requireNonBlank(expectedRankKey, "expectedRankKey");
            expectedPunishmentReason = expectedPunishmentReason == null
                    ? Optional.empty()
                    : expectedPunishmentReason.map(reason -> requireNonBlank(reason, "expectedPunishmentReason"));
        }
    }

    record ExpectedPayloads(List<String> cacheKeys) {
        ExpectedPayloads {
            cacheKeys = List.copyOf(Objects.requireNonNull(cacheKeys, "cacheKeys"));
        }
    }

    record HostPort(String host, int port) {
        HostPort {
            host = requireNonBlank(host, "host");
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
        }

        static HostPort parse(String value) {
            String checked = requireNonBlank(value, "hostPort");
            int separator = checked.lastIndexOf(':');
            if (separator < 1 || separator == checked.length() - 1) {
                throw new IllegalArgumentException("hostPort must use host:port syntax");
            }
            return new HostPort(
                    checked.substring(0, separator),
                    Integer.parseInt(checked.substring(separator + 1)));
        }
    }

    record Result(int subjectCount, int checkCount) {
        Result {
            if (subjectCount < 1) {
                throw new IllegalArgumentException("subjectCount must be positive");
            }
            if (checkCount < subjectCount) {
                throw new IllegalArgumentException("checkCount must be at least subjectCount");
            }
        }
    }

    private static String required(RuntimeEnvironment environment, String name) {
        return environment.value(name)
                .orElseThrow(() -> new RuntimeConfigurationException("Missing required environment binding: " + name));
    }

    private static String requireNonBlank(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
