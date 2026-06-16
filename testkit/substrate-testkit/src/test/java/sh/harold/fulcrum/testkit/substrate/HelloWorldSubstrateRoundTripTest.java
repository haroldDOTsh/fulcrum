package sh.harold.fulcrum.testkit.substrate;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.EventName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.codegen.ContractCodeGenerator;
import sh.harold.fulcrum.data.codegen.GeneratedArtifact;
import sh.harold.fulcrum.data.codegen.GeneratedContractPacket;
import sh.harold.fulcrum.data.contract.AclRuleDeclaration;
import sh.harold.fulcrum.data.contract.CommandDeclaration;
import sh.harold.fulcrum.data.contract.ContractDeclaration;
import sh.harold.fulcrum.data.contract.EventDeclaration;
import sh.harold.fulcrum.data.contract.FieldDeclaration;
import sh.harold.fulcrum.data.contract.FieldType;
import sh.harold.fulcrum.data.contract.ProjectionDeclaration;
import sh.harold.fulcrum.data.contract.SnapshotDeclaration;
import sh.harold.fulcrum.data.contract.TopicDeclaration;
import sh.harold.fulcrum.data.contract.TopicFamily;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HelloWorldSubstrateRoundTripTest {
    private static final String COMMAND_TOPIC = "cmd.hello-world";
    private static final String EVENT_TOPIC = "evt.hello-world";
    private static final String RESPONSE_TOPIC = "rsp.hello-world";
    private static final String AGGREGATE_ID = "aggregate-hello-1";
    private static final Instant REQUESTED_AT = Instant.parse("2026-06-16T07:00:00Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-06-16T07:01:00Z");

    @Test
    void helloWorldCommandRoundTripsThroughSubstrate(@TempDir Path tempDir) throws Exception {
        GeneratedContractPacket packet = new ContractCodeGenerator().generate(helloWorldContract());
        Path classesDir = compileGeneratedSources(packet, tempDir);

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classesDir.toUri().toURL()}, getClass().getClassLoader());
             FulcrumSubstrateStack stack = FulcrumSubstrateStack.create().start()) {
            createTopics(stack.kafkaBootstrapServers(), packet.declaration().topics());
            stack.executePostgres(packet.artifact("migrations/hello-world.sql").contents());

            GeneratedHelloWorld generated = GeneratedHelloWorld.load(loader);
            CommandEnvelope<?> command = generated.command("hello from generated contract");
            sendCommand(stack.kafkaBootstrapServers(), generated, command);

            acceptOneCommand(stack.kafkaBootstrapServers(), generated);
            projectOneEvent(stack, generated);

            assertEquals("hello from generated contract:1", stack.queryPostgresScalar("""
                    SELECT greeting || ':' || revision
                    FROM hello_world_greeting_projection
                    WHERE aggregate_id = 'aggregate-hello-1';
                    """));
            assertEquals("hello from generated contract", stack.getValkey("hello-world:greeting:" + AGGREGATE_ID));
            assertEquals("hello from generated contract", generated.greeting(consumeResponse(stack.kafkaBootstrapServers(), generated)));
        }
    }

    private static void createTopics(String bootstrapServers, List<TopicDeclaration> topicDeclarations) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            List<NewTopic> topics = topicDeclarations.stream()
                    .map(topic -> new NewTopic(topic.name(), 1, (short) 1))
                    .toList();
            admin.createTopics(topics).all().get(20, TimeUnit.SECONDS);
        }
    }

    private static void sendCommand(String bootstrapServers, GeneratedHelloWorld generated, CommandEnvelope<?> command) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(bootstrapServers))) {
            producer.send(new ProducerRecord<>(
                    COMMAND_TOPIC,
                    command.aggregateId().value(),
                    generated.encodeCommand(command.payload()))).get(20, TimeUnit.SECONDS);
        }
    }

    private static void acceptOneCommand(String bootstrapServers, GeneratedHelloWorld generated) throws Exception {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers, "hello-world-authority"));
             KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties(bootstrapServers))) {
            ConsumerRecord<String, String> commandRecord = pollOne(consumer, COMMAND_TOPIC);
            Object commandPayload = generated.decodeCommand(commandRecord.value());
            Object eventPayload = generated.event(generated.greeting(commandPayload), ACCEPTED_AT);
            String encodedEvent = generated.encodeEvent(eventPayload);

            producer.send(new ProducerRecord<>(EVENT_TOPIC, commandRecord.key(), encodedEvent)).get(20, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(RESPONSE_TOPIC, commandRecord.key(), encodedEvent)).get(20, TimeUnit.SECONDS);
        }
    }

    private static void projectOneEvent(FulcrumSubstrateStack stack, GeneratedHelloWorld generated) throws Exception {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(stack.kafkaBootstrapServers(), "hello-world-projection"))) {
            ConsumerRecord<String, String> eventRecord = pollOne(consumer, EVENT_TOPIC);
            Object eventPayload = generated.decodeEvent(eventRecord.value());
            String greeting = generated.greeting(eventPayload);
            stack.executePostgres("""
                    INSERT INTO hello_world_greeting_projection (aggregate_id, greeting, accepted_at, revision)
                    VALUES ('%s', '%s', '%s', 1);
                    """.formatted(sql(eventRecord.key()), sql(greeting), ACCEPTED_AT));
            stack.setValkey("hello-world:greeting:" + eventRecord.key(), greeting);
        }
    }

    private static Object consumeResponse(String bootstrapServers, GeneratedHelloWorld generated) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers, "hello-world-client"))) {
            return generated.decodeEvent(pollOne(consumer, RESPONSE_TOPIC).value());
        }
    }

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer, String topic) {
        consumer.subscribe(List.of(topic));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            for (ConsumerRecord<String, String> record : records) {
                return record;
            }
        }
        throw new AssertionError("Timed out waiting for a Kafka record on " + topic);
    }

    private static Map<String, Object> producerProperties(String bootstrapServers) {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
    }

    private static Map<String, Object> consumerProperties(String bootstrapServers, String groupPrefix) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupPrefix + "-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    private static Path compileGeneratedSources(GeneratedContractPacket packet, Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("sources");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        List<Path> sources = packet.artifacts().stream()
                .filter(artifact -> artifact.path().endsWith(".java"))
                .map(artifact -> writeGeneratedSource(sourceRoot, artifact))
                .toList();

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for generated source validation");
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(sources);
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            boolean compiled = compiler.getTask(
                    new OutputStreamWriter(errors, StandardCharsets.UTF_8),
                    fileManager,
                    null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", classesDir.toString()),
                    null,
                    compilationUnits).call();
            assertTrue(compiled, () -> errors.toString(StandardCharsets.UTF_8));
        }
        return classesDir;
    }

    private static Path writeGeneratedSource(Path sourceRoot, GeneratedArtifact artifact) {
        try {
            Path source = sourceRoot.resolve(artifact.path().replace("src/main/java/", ""));
            Files.createDirectories(source.getParent());
            Files.writeString(source, artifact.contents(), StandardCharsets.UTF_8);
            return source;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write generated source " + artifact.path(), exception);
        }
    }

    private static ContractDeclaration helloWorldContract() {
        return new ContractDeclaration(
                new ContractName("hello-world"),
                List.of(new CommandDeclaration(
                        new CommandName("say-hello"),
                        "SayHello",
                        List.of(
                                new FieldDeclaration("greeting", FieldType.STRING),
                                new FieldDeclaration("requestedAt", FieldType.INSTANT)),
                        false)),
                List.of(new EventDeclaration(
                        new EventName("greeting-accepted"),
                        "GreetingAccepted",
                        List.of(
                                new FieldDeclaration("greeting", FieldType.STRING),
                                new FieldDeclaration("acceptedAt", FieldType.INSTANT)))),
                Optional.of(new SnapshotDeclaration(
                        "HelloWorldSnapshot",
                        List.of(
                                new FieldDeclaration("greeting", FieldType.STRING),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(new ProjectionDeclaration(
                        "helloWorldGreetingProjection",
                        "hello_world_greeting_projection",
                        List.of(
                                new FieldDeclaration("aggregateId", FieldType.STRING),
                                new FieldDeclaration("greeting", FieldType.STRING),
                                new FieldDeclaration("acceptedAt", FieldType.INSTANT),
                                new FieldDeclaration("revision", FieldType.LONG)))),
                List.of(
                        new TopicDeclaration(COMMAND_TOPIC, TopicFamily.COMMAND),
                        new TopicDeclaration(EVENT_TOPIC, TopicFamily.EVENT),
                        new TopicDeclaration("state.hello-world", TopicFamily.STATE),
                        new TopicDeclaration(RESPONSE_TOPIC, TopicFamily.RESPONSE)),
                List.of(
                        new AclRuleDeclaration(COMMAND_TOPIC, List.of("hello-world-client"), List.of("hello-world-authority")),
                        new AclRuleDeclaration(EVENT_TOPIC, List.of("hello-world-authority"), List.of("hello-world-projection")),
                        new AclRuleDeclaration("state.hello-world", List.of("hello-world-authority"), List.of("hello-world-projection")),
                        new AclRuleDeclaration(RESPONSE_TOPIC, List.of("hello-world-authority"), List.of("hello-world-client"))));
    }

    private static String sql(String value) {
        return value.replace("'", "''");
    }

    private record GeneratedHelloWorld(
            Object client,
            Object serializer,
            Class<?> commandPayloadClass,
            Class<?> eventPayloadClass,
            Constructor<?> commandConstructor,
            Constructor<?> eventConstructor,
            Method commandClientMethod,
            Method encodeCommandMethod,
            Method decodeCommandMethod,
            Method encodeEventMethod,
            Method decodeEventMethod) {
        static GeneratedHelloWorld load(ClassLoader loader) throws Exception {
            Class<?> commandPayloadClass = loader.loadClass("sh.harold.fulcrum.generated.contracts.SayHello");
            Class<?> eventPayloadClass = loader.loadClass("sh.harold.fulcrum.generated.contracts.GreetingAccepted");
            Class<?> clientClass = loader.loadClass("sh.harold.fulcrum.generated.contracts.HelloWorldCommandClient");
            Class<?> serializerClass = loader.loadClass("sh.harold.fulcrum.generated.contracts.HelloWorldSerializer");

            GeneratedHelloWorld generated = new GeneratedHelloWorld(
                    clientClass.getConstructor().newInstance(),
                    serializerClass.getConstructor().newInstance(),
                    commandPayloadClass,
                    eventPayloadClass,
                    commandPayloadClass.getConstructor(String.class, Instant.class),
                    eventPayloadClass.getConstructor(String.class, Instant.class),
                    clientClass.getMethod(
                            "sayHello",
                            CommandId.class,
                            IdempotencyKey.class,
                            PrincipalId.class,
                            AggregateId.class,
                            TraceEnvelope.class,
                            Optional.class,
                            commandPayloadClass),
                    serializerClass.getMethod("encodeSayHello", commandPayloadClass),
                    serializerClass.getMethod("decodeSayHello", String.class),
                    serializerClass.getMethod("encodeGreetingAccepted", eventPayloadClass),
                    serializerClass.getMethod("decodeGreetingAccepted", String.class));
            return generated;
        }

        CommandEnvelope<?> command(String greeting) throws Exception {
            Object payload = commandConstructor.newInstance(greeting, REQUESTED_AT);
            return (CommandEnvelope<?>) commandClientMethod.invoke(
                    client,
                    new CommandId("command-hello-1"),
                    new IdempotencyKey("hello-idempotency-1"),
                    new PrincipalId("principal-client-1"),
                    new AggregateId(AGGREGATE_ID),
                    new TraceEnvelope(
                            "trace-hello-1",
                            "span-client-1",
                            Optional.empty(),
                            REQUESTED_AT,
                            "hello-world-client",
                            new InstanceId("instance-client-1")),
                    Optional.empty(),
                    payload);
        }

        Object event(String greeting, Instant acceptedAt) throws Exception {
            return eventConstructor.newInstance(greeting, acceptedAt);
        }

        String encodeCommand(Object payload) throws Exception {
            return (String) encodeCommandMethod.invoke(serializer, commandPayloadClass.cast(payload));
        }

        Object decodeCommand(String encoded) throws Exception {
            return decodeCommandMethod.invoke(serializer, encoded);
        }

        String encodeEvent(Object payload) throws Exception {
            return (String) encodeEventMethod.invoke(serializer, eventPayloadClass.cast(payload));
        }

        Object decodeEvent(String encoded) {
            try {
                return decodeEventMethod.invoke(serializer, encoded);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not decode generated event", exception);
            }
        }

        String greeting(Object payload) throws Exception {
            return (String) payload.getClass().getMethod("greeting").invoke(payload);
        }
    }
}
