package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.UpsertPlayerProfile;
import sh.harold.fulcrum.standard.punishment.IssuePunishment;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.rank.GrantRank;
import sh.harold.fulcrum.standard.rank.RankAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public final class LobbyCapabilitySeedProvisioner {
    static final String DEFAULT_ACCEPTED_USERNAME = "FulcrumBotOne";
    static final String DEFAULT_SECOND_ACCEPTED_USERNAME = "FulcrumBotTwo";
    static final String DEFAULT_SCALE_OUT_ACCEPTED_USERNAME = "FulcrumBotFour";
    static final String DEFAULT_ACCEPTED_RANK_KEY = "Admin";
    static final String DEFAULT_DENIED_RANK_KEY = "Member";
    static final String DEFAULT_DENIED_PUNISHMENT_REASON = "Banned from the lobby";
    static final Instant DEFAULT_SEEDED_AT = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant DEFAULT_DENIED_PUNISHMENT_EXPIRES_AT = Instant.parse("2099-01-01T00:00:00Z");
    static final String DEFAULT_PRINCIPAL_ID = "principal-lobby-capability-seed";
    static final String DEFAULT_INSTANCE_ID = "instance-lobby-capability-seed";

    private LobbyCapabilitySeedProvisioner() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("LobbyCapabilitySeedProvisioner reads configuration from environment");
        }
        RuntimeEnvironment environment = RuntimeEnvironment.system();
        Config config = Config.fromEnvironment(environment);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(config.kafkaBootstrapServers()))) {
            Result result = provision(config, producer, Clock.systemUTC());
            System.out.println("publishedCapabilitySeedSubjects=" + result.seededSubjects().size());
            System.out.println("publishedCapabilitySeedCommands=" + result.publishedCommandCount());
            result.seededSubjects().forEach(subject -> System.out.println("seededSubject="
                    + subject.seed().subjectId().value()
                    + " username=" + subject.seed().username()
                    + " punishment=" + subject.punishmentCommand().isPresent()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed to publish lobby capability seed commands", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("failed to publish lobby capability seed commands", exception);
        }
    }

    static Result provision(
            Config config,
            Producer<String, String> producer,
            Clock clock) throws ExecutionException, InterruptedException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(clock, "clock");

        List<SeededSubject> seededSubjects = new ArrayList<>();
        for (SubjectSeed seed : config.subjects()) {
            AuthorityCommand<UpsertPlayerProfile> profileCommand = playerProfileCommand(config, seed, clock.instant());
            producer.send(new ProducerRecord<>(
                    config.playerProfileCommandTopic(),
                    profileCommand.envelope().aggregateId().value(),
                    StandardCapabilityAuthorityWireCodec.encodePlayerProfileCommand(profileCommand))).get();

            AuthorityCommand<GrantRank> rankCommand = rankCommand(config, seed, clock.instant());
            producer.send(new ProducerRecord<>(
                    config.rankCommandTopic(),
                    rankCommand.envelope().aggregateId().value(),
                    StandardCapabilityAuthorityWireCodec.encodeRankCommand(rankCommand))).get();

            Optional<AuthorityCommand<IssuePunishment>> punishmentCommand = Optional.empty();
            if (seed.punishment().isPresent()) {
                AuthorityCommand<IssuePunishment> command = punishmentCommand(
                        config,
                        seed,
                        seed.punishment().orElseThrow(),
                        clock.instant());
                producer.send(new ProducerRecord<>(
                        config.punishmentCommandTopic(),
                        command.envelope().aggregateId().value(),
                        StandardCapabilityAuthorityWireCodec.encodePunishmentCommand(command))).get();
                punishmentCommand = Optional.of(command);
            }

            seededSubjects.add(new SeededSubject(seed, profileCommand, rankCommand, punishmentCommand));
        }
        return new Result(seededSubjects);
    }

    static SubjectId offlineModeSubjectId(String username) {
        String checked = requireNonBlank(username, "username");
        return new SubjectId(UUID.nameUUIDFromBytes(("OfflinePlayer:" + checked).getBytes(StandardCharsets.UTF_8)));
    }

    private static AuthorityCommand<UpsertPlayerProfile> playerProfileCommand(
            Config config,
            SubjectSeed seed,
            Instant receivedAt) {
        UpsertPlayerProfile payload = new UpsertPlayerProfile(
                seed.subjectId(),
                seed.displayName(),
                config.seededAt(),
                0L);
        return command(
                config,
                seed,
                PlayerProfileContracts.CONTRACT,
                StandardCapabilityAuthorityWireCodec.UPSERT_PROFILE_COMMAND,
                PlayerProfileAuthority.aggregateId(seed.subjectId()),
                "profile",
                payload,
                payloadFingerprint(
                        PlayerProfileContracts.CONTRACT.value(),
                        StandardCapabilityAuthorityWireCodec.UPSERT_PROFILE_COMMAND,
                        seed.subjectId().value().toString(),
                        seed.displayName(),
                        config.seededAt().toString(),
                        "0"),
                receivedAt);
    }

    private static AuthorityCommand<GrantRank> rankCommand(
            Config config,
            SubjectSeed seed,
            Instant receivedAt) {
        GrantRank payload = new GrantRank(
                seed.subjectId(),
                seed.rankKey(),
                config.seededAt(),
                0L);
        return command(
                config,
                seed,
                RankContracts.CONTRACT,
                StandardCapabilityAuthorityWireCodec.GRANT_RANK_COMMAND,
                RankAuthority.aggregateId(seed.subjectId()),
                "rank",
                payload,
                payloadFingerprint(
                        RankContracts.CONTRACT.value(),
                        StandardCapabilityAuthorityWireCodec.GRANT_RANK_COMMAND,
                        seed.subjectId().value().toString(),
                        seed.rankKey(),
                        config.seededAt().toString(),
                        "0"),
                receivedAt);
    }

    private static AuthorityCommand<IssuePunishment> punishmentCommand(
            Config config,
            SubjectSeed seed,
            PunishmentSeed punishment,
            Instant receivedAt) {
        IssuePunishment payload = new IssuePunishment(
                seed.subjectId(),
                punishment.punishmentId(),
                punishment.reason(),
                config.seededAt(),
                punishment.expiresAt(),
                0L);
        return command(
                config,
                seed,
                PunishmentContracts.CONTRACT,
                StandardCapabilityAuthorityWireCodec.ISSUE_PUNISHMENT_COMMAND,
                PunishmentAuthority.aggregateId(seed.subjectId()),
                "punishment",
                payload,
                payloadFingerprint(
                        PunishmentContracts.CONTRACT.value(),
                        StandardCapabilityAuthorityWireCodec.ISSUE_PUNISHMENT_COMMAND,
                        seed.subjectId().value().toString(),
                        punishment.punishmentId(),
                        punishment.reason(),
                        config.seededAt().toString(),
                        punishment.expiresAt().toString(),
                        "0"),
                receivedAt);
    }

    private static <C extends sh.harold.fulcrum.api.contract.CommandPayload> AuthorityCommand<C> command(
            Config config,
            SubjectSeed seed,
            ContractName contract,
            String commandName,
            sh.harold.fulcrum.api.contract.AggregateId aggregateId,
            String commandKind,
            C payload,
            String payloadFingerprint,
            Instant receivedAt) {
        String subjectSuffix = shortSubject(seed.subjectId());
        String idSuffix = commandKind + "-" + subjectSuffix;
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-lobby-capability-seed-" + idSuffix),
                        new IdempotencyKey("idem-lobby-capability-seed-" + idSuffix),
                        config.principalId(),
                        aggregateId,
                        contract,
                        new CommandName(commandName),
                        new TraceEnvelope(
                                "trace-lobby-capability-seed-" + subjectSuffix,
                                "span-lobby-capability-seed-" + idSuffix,
                                Optional.empty(),
                                receivedAt,
                                "capability-seed-provisioner",
                                config.instanceId()),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                config.principalId(),
                1,
                Optional.of(new Revision(0)),
                payloadFingerprint,
                receivedAt);
    }

    private static String payloadFingerprint(String... values) {
        return sha256(String.join("|", values).getBytes(StandardCharsets.UTF_8));
    }

    private static String shortSubject(SubjectId subjectId) {
        return subjectId.value().toString().replace("-", "").substring(0, 16);
    }

    private static Properties producerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return properties;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    record Config(
            String kafkaBootstrapServers,
            String playerProfileCommandTopic,
            String rankCommandTopic,
            String punishmentCommandTopic,
            PrincipalId principalId,
            InstanceId instanceId,
            Instant seededAt,
            List<SubjectSeed> subjects) {
        Config {
            kafkaBootstrapServers = requireNonBlank(kafkaBootstrapServers, "kafkaBootstrapServers");
            playerProfileCommandTopic = requireNonBlank(playerProfileCommandTopic, "playerProfileCommandTopic");
            rankCommandTopic = requireNonBlank(rankCommandTopic, "rankCommandTopic");
            punishmentCommandTopic = requireNonBlank(punishmentCommandTopic, "punishmentCommandTopic");
            principalId = Objects.requireNonNull(principalId, "principalId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            seededAt = Objects.requireNonNull(seededAt, "seededAt");
            subjects = List.copyOf(Objects.requireNonNull(subjects, "subjects"));
            if (subjects.isEmpty()) {
                throw new IllegalArgumentException("subjects must not be empty");
            }
            if (subjects.stream().map(SubjectSeed::subjectId).distinct().count() != subjects.size()) {
                throw new IllegalArgumentException("seeded subjects must have distinct SubjectIds");
            }
            for (SubjectSeed subject : subjects) {
                if (subject.punishment().isPresent()
                        && !subject.punishment().orElseThrow().expiresAt().isAfter(seededAt)) {
                    throw new IllegalArgumentException("punishment expiresAt must be after seededAt");
                }
            }
        }

        static Config fromEnvironment(RuntimeEnvironment environment) {
            Objects.requireNonNull(environment, "environment");
            Instant seededAt = environment.value("FULCRUM_LOBBY_SEEDED_AT")
                    .map(Instant::parse)
                    .orElse(DEFAULT_SEEDED_AT);
            SubjectSeed accepted = acceptedSubject(environment);
            SubjectSeed secondAccepted = secondAcceptedSubject(environment);
            SubjectSeed scaleOutAccepted = scaleOutAcceptedSubject(environment);
            List<SubjectSeed> subjects = new ArrayList<>();
            subjects.add(accepted);
            subjects.add(secondAccepted);
            subjects.add(scaleOutAccepted);
            deniedSubject(environment).ifPresent(subjects::add);
            return new Config(
                    required(environment, "FULCRUM_KAFKA_BOOTSTRAP_SERVERS"),
                    environment.value("FULCRUM_PLAYER_PROFILE_COMMAND_TOPIC").orElse(PlayerProfileContracts.COMMAND_TOPIC),
                    environment.value("FULCRUM_RANK_COMMAND_TOPIC").orElse(RankContracts.COMMAND_TOPIC),
                    environment.value("FULCRUM_PUNISHMENT_COMMAND_TOPIC").orElse(PunishmentContracts.COMMAND_TOPIC),
                    new PrincipalId(environment.value("FULCRUM_PRINCIPAL_ID").orElse(DEFAULT_PRINCIPAL_ID)),
                    new InstanceId(environment.value("FULCRUM_INSTANCE_ID").orElse(DEFAULT_INSTANCE_ID)),
                    seededAt,
                    subjects);
        }

        private static SubjectSeed acceptedSubject(RuntimeEnvironment environment) {
            String username = environment.value("FULCRUM_LOBBY_ACCEPTED_USERNAME").orElse(DEFAULT_ACCEPTED_USERNAME);
            return new SubjectSeed(
                    subjectId(environment, "FULCRUM_LOBBY_ACCEPTED_SUBJECT_ID", username),
                    username,
                    environment.value("FULCRUM_LOBBY_ACCEPTED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_ACCEPTED_RANK_KEY").orElse(DEFAULT_ACCEPTED_RANK_KEY),
                    Optional.empty());
        }

        private static SubjectSeed secondAcceptedSubject(RuntimeEnvironment environment) {
            String username = environment.value("FULCRUM_LOBBY_SECOND_ACCEPTED_USERNAME")
                    .orElse(DEFAULT_SECOND_ACCEPTED_USERNAME);
            return new SubjectSeed(
                    subjectId(environment, "FULCRUM_LOBBY_SECOND_ACCEPTED_SUBJECT_ID", username),
                    username,
                    environment.value("FULCRUM_LOBBY_SECOND_ACCEPTED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_SECOND_ACCEPTED_RANK_KEY").orElse(DEFAULT_ACCEPTED_RANK_KEY),
                    Optional.empty());
        }

        private static SubjectSeed scaleOutAcceptedSubject(RuntimeEnvironment environment) {
            String username = environment.value("FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_USERNAME")
                    .orElse(DEFAULT_SCALE_OUT_ACCEPTED_USERNAME);
            return new SubjectSeed(
                    subjectId(environment, "FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_SUBJECT_ID", username),
                    username,
                    environment.value("FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_SCALE_OUT_ACCEPTED_RANK_KEY").orElse(DEFAULT_ACCEPTED_RANK_KEY),
                    Optional.empty());
        }

        private static Optional<SubjectSeed> deniedSubject(RuntimeEnvironment environment) {
            Optional<String> deniedUsername = environment.value("FULCRUM_LOBBY_DENIED_USERNAME");
            Optional<String> deniedSubjectId = environment.value("FULCRUM_LOBBY_DENIED_SUBJECT_ID");
            if (deniedUsername.isEmpty() && deniedSubjectId.isEmpty()) {
                return Optional.empty();
            }
            String username = deniedUsername.orElse("DeniedSubject");
            SubjectId subjectId = deniedSubjectId
                    .map(value -> new SubjectId(UUID.fromString(value)))
                    .orElseGet(() -> offlineModeSubjectId(username));
            PunishmentSeed punishment = new PunishmentSeed(
                    environment.value("FULCRUM_LOBBY_DENIED_PUNISHMENT_ID")
                            .orElse("punishment-lobby-denied-" + shortSubject(subjectId)),
                    environment.value("FULCRUM_LOBBY_DENIED_PUNISHMENT_REASON")
                            .orElse(DEFAULT_DENIED_PUNISHMENT_REASON),
                    environment.value("FULCRUM_LOBBY_DENIED_PUNISHMENT_EXPIRES_AT")
                            .map(Instant::parse)
                            .orElse(DEFAULT_DENIED_PUNISHMENT_EXPIRES_AT));
            return Optional.of(new SubjectSeed(
                    subjectId,
                    username,
                    environment.value("FULCRUM_LOBBY_DENIED_DISPLAY_NAME").orElse(username),
                    environment.value("FULCRUM_LOBBY_DENIED_RANK_KEY").orElse(DEFAULT_DENIED_RANK_KEY),
                    Optional.of(punishment)));
        }

        private static SubjectId subjectId(RuntimeEnvironment environment, String envName, String username) {
            return environment.value(envName)
                    .map(value -> new SubjectId(UUID.fromString(value)))
                    .orElseGet(() -> offlineModeSubjectId(username));
        }
    }

    record SubjectSeed(
            SubjectId subjectId,
            String username,
            String displayName,
            String rankKey,
            Optional<PunishmentSeed> punishment) {
        SubjectSeed {
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            username = requireNonBlank(username, "username");
            displayName = requireNonBlank(displayName, "displayName");
            rankKey = requireNonBlank(rankKey, "rankKey");
            punishment = punishment == null ? Optional.empty() : punishment;
        }
    }

    record PunishmentSeed(
            String punishmentId,
            String reason,
            Instant expiresAt) {
        PunishmentSeed {
            punishmentId = requireNonBlank(punishmentId, "punishmentId");
            reason = requireNonBlank(reason, "reason");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record SeededSubject(
            SubjectSeed seed,
            AuthorityCommand<UpsertPlayerProfile> playerProfileCommand,
            AuthorityCommand<GrantRank> rankCommand,
            Optional<AuthorityCommand<IssuePunishment>> punishmentCommand) {
        SeededSubject {
            seed = Objects.requireNonNull(seed, "seed");
            playerProfileCommand = Objects.requireNonNull(playerProfileCommand, "playerProfileCommand");
            rankCommand = Objects.requireNonNull(rankCommand, "rankCommand");
            punishmentCommand = punishmentCommand == null ? Optional.empty() : punishmentCommand;
        }
    }

    record Result(List<SeededSubject> seededSubjects) {
        Result {
            seededSubjects = List.copyOf(Objects.requireNonNull(seededSubjects, "seededSubjects"));
            if (seededSubjects.isEmpty()) {
                throw new IllegalArgumentException("seededSubjects must not be empty");
            }
        }

        int publishedCommandCount() {
            return seededSubjects.stream()
                    .mapToInt(subject -> 2 + (subject.punishmentCommand().isPresent() ? 1 : 0))
                    .sum();
        }
    }

    private static String required(RuntimeEnvironment environment, String name) {
        return environment.value(name)
                .orElseThrow(() -> new RuntimeConfigurationException("Missing required environment binding: " + name));
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
