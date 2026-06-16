package sh.harold.fulcrum.data.codegen;

import sh.harold.fulcrum.data.contract.CommandDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.EventDeclaration;
import sh.harold.fulcrum.data.contract.FieldDeclaration;
import sh.harold.fulcrum.data.contract.FieldType;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ContractCodeGenerator {
    private static final String GENERATED_PACKAGE = "sh.harold.fulcrum.generated.contracts";
    private static final String GENERATOR_VERSION = "contract-codegen-v1";

    public GeneratedContractPacket generate(ContractDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        if (declaration.commands().isEmpty()) {
            throw new IllegalArgumentException("contract must declare at least one command");
        }
        if (declaration.events().isEmpty()) {
            throw new IllegalArgumentException("contract must declare at least one event");
        }
        if (declaration.projections().isEmpty()) {
            throw new IllegalArgumentException("contract must declare at least one projection for migration output");
        }

        String domain = declaration.name().value();
        String classPrefix = upperCamel(domain);
        String fingerprint = fingerprint(declaration);
        List<GeneratedArtifact> artifacts = new ArrayList<>();

        artifacts.add(new GeneratedArtifact("contracts/" + domain + "/contract-ledger.json", ledger(declaration, fingerprint)));
        declaration.commands().forEach(command -> artifacts.add(new GeneratedArtifact("src/main/java/sh/harold/fulcrum/generated/contracts/" + command.payloadType() + ".java", payloadSource(command.payloadType(), "CommandPayload", command.fields()))));
        declaration.events().forEach(event -> artifacts.add(new GeneratedArtifact("src/main/java/sh/harold/fulcrum/generated/contracts/" + event.payloadType() + ".java", payloadSource(event.payloadType(), "EventPayload", event.fields()))));
        artifacts.add(new GeneratedArtifact("src/main/java/sh/harold/fulcrum/generated/contracts/" + classPrefix + "CommandClient.java", clientSource(declaration, classPrefix)));
        artifacts.add(new GeneratedArtifact("src/main/java/sh/harold/fulcrum/generated/contracts/" + classPrefix + "Serializer.java", serializerSource(declaration, classPrefix)));
        artifacts.add(new GeneratedArtifact("src/main/java/sh/harold/fulcrum/generated/contracts/" + classPrefix + "AuthorityStub.java", authorityStubSource(declaration, classPrefix)));
        artifacts.add(new GeneratedArtifact("manifests/" + domain + ".acl.json", aclManifest(declaration)));
        artifacts.add(new GeneratedArtifact("manifests/" + domain + ".topics.json", topicManifest(declaration)));
        artifacts.add(new GeneratedArtifact("migrations/" + domain + ".sql", migration(declaration)));

        return new GeneratedContractPacket(declaration, fingerprint, artifacts.stream()
                .sorted(Comparator.comparing(GeneratedArtifact::path))
                .toList());
    }

    private static String ledger(ContractDeclaration declaration, String fingerprint) {
        String domain = declaration.name().value();
        String classPrefix = upperCamel(domain);
        return """
                {
                  "contract": "%s",
                  "generator": "%s",
                  "fingerprint": "%s",
                  "artifacts": [
                %s
                    "src/main/java/sh/harold/fulcrum/generated/contracts/%sCommandClient.java",
                    "src/main/java/sh/harold/fulcrum/generated/contracts/%sSerializer.java",
                    "src/main/java/sh/harold/fulcrum/generated/contracts/%sAuthorityStub.java",
                    "manifests/%s.acl.json",
                    "manifests/%s.topics.json",
                    "migrations/%s.sql"
                  ]
                }
                """.formatted(domain, GENERATOR_VERSION, fingerprint, payloadArtifactLines(declaration), classPrefix, classPrefix, classPrefix, domain, domain, domain);
    }

    private static String clientSource(ContractDeclaration declaration, String classPrefix) {
        CommandDeclaration command = onlyCommand(declaration);
        String commandType = command.payloadType();
        return """
                package %s;

                import sh.harold.fulcrum.api.contract.AggregateId;
                import sh.harold.fulcrum.api.contract.CommandEnvelope;
                import sh.harold.fulcrum.api.contract.CommandId;
                import sh.harold.fulcrum.api.contract.CommandName;
                import sh.harold.fulcrum.api.contract.ContractName;
                import sh.harold.fulcrum.api.contract.IdempotencyKey;
                import sh.harold.fulcrum.api.contract.PrincipalId;
                import sh.harold.fulcrum.api.contract.TraceEnvelope;

                import java.time.Instant;
                import java.util.Optional;

                public final class %sCommandClient {
                    private static final ContractName CONTRACT = new ContractName("%s");
                    private static final CommandName COMMAND = new CommandName("%s");

                    public CommandEnvelope<%s> %s(
                            CommandId commandId,
                            IdempotencyKey idempotencyKey,
                            PrincipalId principalId,
                            AggregateId aggregateId,
                            TraceEnvelope traceEnvelope,
                            Optional<Instant> deadlineAt,
                            %s payload) {
                        return new CommandEnvelope<>(
                                commandId,
                                idempotencyKey,
                                principalId,
                                aggregateId,
                                CONTRACT,
                                COMMAND,
                                traceEnvelope,
                                deadlineAt,
                                payload);
                    }
                }
                """.formatted(
                GENERATED_PACKAGE,
                classPrefix,
                declaration.name().value(),
                command.name().value(),
                commandType,
                lowerCamel(command.name().value()),
                commandType);
    }

    private static String serializerSource(ContractDeclaration declaration, String classPrefix) {
        CommandDeclaration command = onlyCommand(declaration);
        EventDeclaration event = onlyEvent(declaration);
        return """
                package %s;

                import java.time.Instant;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.Objects;
                import java.util.StringJoiner;

                public final class %sSerializer {
                    public String encode%s(%s payload) {
                        Objects.requireNonNull(payload, "payload");
                        StringJoiner joiner = new StringJoiner("\\n");
                %s
                        return joiner.toString();
                    }

                    public %s decode%s(String encoded) {
                        Map<String, String> values = fields(encoded);
                        return new %s(%s);
                    }

                    public String encode%s(%s payload) {
                        Objects.requireNonNull(payload, "payload");
                        StringJoiner joiner = new StringJoiner("\\n");
                %s
                        return joiner.toString();
                    }

                    public %s decode%s(String encoded) {
                        Map<String, String> values = fields(encoded);
                        return new %s(%s);
                    }

                    private static Map<String, String> fields(String encoded) {
                        Map<String, String> values = new LinkedHashMap<>();
                        for (String line : encoded.split("\\\\R")) {
                            int separator = line.indexOf('=');
                            if (separator <= 0) {
                                throw new IllegalArgumentException("Malformed encoded field: " + line);
                            }
                            values.put(line.substring(0, separator), unescape(line.substring(separator + 1)));
                        }
                        return values;
                    }

                    private static String escape(Object value) {
                        return String.valueOf(value).replace("\\\\", "\\\\\\\\").replace("\\n", "\\\\n").replace("=", "\\\\=");
                    }

                    private static String unescape(String value) {
                        StringBuilder result = new StringBuilder();
                        boolean escaping = false;
                        for (int i = 0; i < value.length(); i++) {
                            char current = value.charAt(i);
                            if (escaping) {
                                result.append(current == 'n' ? '\\n' : current);
                                escaping = false;
                            } else if (current == '\\\\') {
                                escaping = true;
                            } else {
                                result.append(current);
                            }
                        }
                        if (escaping) {
                            result.append('\\\\');
                        }
                        return result.toString();
                    }
                }
                """.formatted(
                GENERATED_PACKAGE,
                classPrefix,
                command.payloadType(),
                command.payloadType(),
                encodeLines(command.fields(), "payload"),
                command.payloadType(),
                command.payloadType(),
                command.payloadType(),
                decodeArguments(command.fields()),
                event.payloadType(),
                event.payloadType(),
                encodeLines(event.fields(), "payload"),
                event.payloadType(),
                event.payloadType(),
                event.payloadType(),
                decodeArguments(event.fields()));
    }

    private static String authorityStubSource(ContractDeclaration declaration, String classPrefix) {
        CommandDeclaration command = onlyCommand(declaration);
        EventDeclaration event = onlyEvent(declaration);
        return """
                package %s;

                import sh.harold.fulcrum.api.contract.CommandEnvelope;

                public interface %sAuthorityStub {
                    %s handle%s(CommandEnvelope<%s> command);
                }
                """.formatted(
                GENERATED_PACKAGE,
                classPrefix,
                event.payloadType(),
                command.payloadType(),
                command.payloadType());
    }

    private static String topicManifest(ContractDeclaration declaration) {
        String topics = declaration.topics().stream()
                .map(topic -> """
                            {
                              "name": "%s",
                              "family": "%s"
                            }""".formatted(topic.name(), topic.family()))
                .collect(Collectors.joining(",\n"));
        return """
                {
                  "contract": "%s",
                  "topics": [
                %s
                  ]
                }
                """.formatted(declaration.name().value(), indent(topics, 4));
    }

    private static String aclManifest(ContractDeclaration declaration) {
        String rules = declaration.aclRules().stream()
                .map(rule -> """
                            {
                              "resource": "%s",
                              "producers": [%s],
                              "consumers": [%s]
                            }""".formatted(rule.resource(), quotedList(rule.producers()), quotedList(rule.consumers())))
                .collect(Collectors.joining(",\n"));
        return """
                {
                  "contract": "%s",
                  "rules": [
                %s
                  ]
                }
                """.formatted(declaration.name().value(), indent(rules, 4));
    }

    private static String migration(ContractDeclaration declaration) {
        ProjectionDeclaration projection = declaration.projections().getFirst();
        String columns = projection.fields().stream()
                .map(field -> "    " + snake(field.name()) + " " + field.type().migrationType() + (field.nullable() ? "" : " NOT NULL"))
                .collect(Collectors.joining(",\n"));
        return """
                -- Generated by %s for contract %s.
                -- Inert migration artifact; services must not execute schema creation at startup.
                CREATE TABLE %s (
                %s
                );
                """.formatted(GENERATOR_VERSION, declaration.name().value(), projection.relationName(), columns);
    }

    private static String payloadSource(String payloadType, String markerInterface, List<FieldDeclaration> fields) {
        String components = fields.stream()
                .map(field -> field.type().javaType() + " " + field.name())
                .collect(Collectors.joining(", "));
        String validations = fields.stream()
                .filter(field -> !field.nullable() && field.type() != FieldType.LONG)
                .map(field -> "            " + field.name() + " = Objects.requireNonNull(" + field.name() + ", \"" + field.name() + "\");")
                .collect(Collectors.joining("\n"));
        String instantImport = fields.stream().anyMatch(field -> field.type() == FieldType.INSTANT)
                ? "import java.time.Instant;\n"
                : "";
        return """
                package %s;

                import sh.harold.fulcrum.api.contract.%s;

                %simport java.util.Objects;

                public record %s(%s) implements %s {
                    public %s {
                %s
                    }
                }
                """.formatted(GENERATED_PACKAGE, markerInterface, instantImport, payloadType, components, markerInterface, payloadType, validations);
    }

    private static String payloadArtifactLines(ContractDeclaration declaration) {
        List<String> payloadTypes = new ArrayList<>();
        declaration.commands().forEach(command -> payloadTypes.add(command.payloadType()));
        declaration.events().forEach(event -> payloadTypes.add(event.payloadType()));
        return payloadTypes.stream()
                .map(payloadType -> "    \"src/main/java/sh/harold/fulcrum/generated/contracts/" + payloadType + ".java\",")
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static String encodeLines(List<FieldDeclaration> fields, String variableName) {
        return fields.stream()
                .map(field -> "        joiner.add(\"" + field.name() + "=\" + escape(" + variableName + "." + field.name() + "()));")
                .collect(Collectors.joining("\n"));
    }

    private static String decodeArguments(List<FieldDeclaration> fields) {
        return fields.stream()
                .map(field -> switch (field.type()) {
                    case STRING -> "values.get(\"" + field.name() + "\")";
                    case INSTANT -> "Instant.parse(values.get(\"" + field.name() + "\"))";
                    case LONG -> "Long.parseLong(values.get(\"" + field.name() + "\"))";
                })
                .collect(Collectors.joining(", "));
    }

    private static CommandDeclaration onlyCommand(ContractDeclaration declaration) {
        if (declaration.commands().size() != 1) {
            throw new IllegalArgumentException("first generator slice expects exactly one command");
        }
        return declaration.commands().getFirst();
    }

    private static EventDeclaration onlyEvent(ContractDeclaration declaration) {
        if (declaration.events().size() != 1) {
            throw new IllegalArgumentException("first generator slice expects exactly one event");
        }
        return declaration.events().getFirst();
    }

    private static String fingerprint(ContractDeclaration declaration) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical(declaration).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String canonical(ContractDeclaration declaration) {
        StringBuilder builder = new StringBuilder();
        builder.append(declaration.name().value()).append('\n');
        declaration.commands().forEach(command -> builder.append("cmd:")
                .append(command.name().value()).append(':')
                .append(command.payloadType()).append(':')
                .append(command.revisionGuarded()).append(':')
                .append(fields(command.fields())).append('\n'));
        declaration.events().forEach(event -> builder.append("evt:")
                .append(event.name().value()).append(':')
                .append(event.payloadType()).append(':')
                .append(fields(event.fields())).append('\n'));
        declaration.snapshot().ifPresent(snapshot -> builder.append("snapshot:")
                .append(snapshot.payloadType()).append(':')
                .append(fields(snapshot.fields())).append('\n'));
        declaration.projections().forEach(projection -> builder.append("projection:")
                .append(projection.name()).append(':')
                .append(projection.relationName()).append(':')
                .append(fields(projection.fields())).append('\n'));
        declaration.topics().forEach(topic -> builder.append("topic:")
                .append(topic.name()).append(':')
                .append(topic.family()).append('\n'));
        declaration.aclRules().forEach(rule -> builder.append("acl:")
                .append(rule.resource()).append(':')
                .append(String.join(",", rule.producers())).append(':')
                .append(String.join(",", rule.consumers())).append('\n'));
        return builder.toString();
    }

    private static String fields(List<FieldDeclaration> fields) {
        return fields.stream()
                .map(field -> field.name() + "/" + field.type() + "/" + field.nullable())
                .collect(Collectors.joining(","));
    }

    private static String upperCamel(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
                result.append(part.substring(1));
            }
        }
        return result.toString();
    }

    private static String lowerCamel(String value) {
        String upper = upperCamel(value);
        return upper.substring(0, 1).toLowerCase(Locale.ROOT) + upper.substring(1);
    }

    private static String snake(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return value.lines().map(line -> prefix + line).collect(Collectors.joining("\n"));
    }

    private static String quotedList(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", "));
    }
}
