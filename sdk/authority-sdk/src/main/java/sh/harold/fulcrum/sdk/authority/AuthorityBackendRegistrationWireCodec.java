package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.EventName;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.capability.api.CapabilityAuthorityDeclaration;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
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
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class AuthorityBackendRegistrationWireCodec {
    public static final String CONTENT_TYPE = "text/plain; charset=utf-8";
    private static final String SCHEMA = "authority-backend-registration-v1";
    private static final String REQUEST = "request";
    private static final String RECEIPT = "receipt";

    private AuthorityBackendRegistrationWireCodec() {
    }

    public static String encodeRequest(AuthorityBackendRegistrationRequest request) {
        AuthorityBackendRegistrationRequest checked = Objects.requireNonNull(request, "request");
        Map<String, String> fields = baseFields(REQUEST);
        putDescriptor(fields, "descriptor", checked.descriptor());
        putSecurityContext(fields, checked.securityContext());
        fields.put("descriptorDigest", checked.descriptorDigest());
        fields.put("bundleDigest", checked.bundleDigest());
        putArtifactVerification(fields, checked.artifactVerification());
        fields.put("sdkVersion", checked.sdkVersion());
        fields.put("requestedAt", checked.requestedAt().toString());
        return encode(fields);
    }

    public static AuthorityBackendRegistrationRequest decodeRequest(String wireValue) {
        Map<String, String> fields = decode(wireValue);
        requireSchema(fields, REQUEST);
        return new AuthorityBackendRegistrationRequest(
                descriptor(fields, "descriptor"),
                securityContext(fields),
                required(fields, "descriptorDigest"),
                required(fields, "bundleDigest"),
                artifactVerification(fields),
                required(fields, "sdkVersion"),
                Instant.parse(required(fields, "requestedAt")));
    }

    public static String encodeReceipt(AuthorityBackendRegistrationReceipt receipt) {
        AuthorityBackendRegistrationReceipt checked = Objects.requireNonNull(receipt, "receipt");
        Map<String, String> fields = baseFields(RECEIPT);
        fields.put("status", checked.status().name());
        fields.put("capabilityId", checked.capabilityId().value());
        fields.put("descriptorDigest", checked.descriptorDigest());
        fields.put("bundleDigest", checked.bundleDigest());
        fields.put("materializationPlanHash", checked.materializationPlanHash());
        putOptional(fields, "principalId", checked.principalId().map(PrincipalId::value));
        putOptional(fields, "grantFingerprint", checked.grantFingerprint());
        fields.put("fencingEpoch", Long.toString(checked.fencingEpoch()));
        fields.put("issuedAt", checked.issuedAt().toString());
        fields.put("receiptId", checked.receiptId());
        putOptional(fields, "rejectionReason", checked.rejectionReason().map(Enum::name));
        putOptional(fields, "artifactVerificationEvidence", checked.artifactVerificationEvidence());
        fields.put("signature", checked.signature());
        return encode(fields);
    }

    public static AuthorityBackendRegistrationReceipt decodeReceipt(String wireValue) {
        Map<String, String> fields = decode(wireValue);
        requireSchema(fields, RECEIPT);
        Optional<PrincipalId> principalId = optional(fields, "principalId").map(PrincipalId::new);
        Optional<AuthorityBackendRegistrationRejectionReason> rejectionReason = optional(fields, "rejectionReason")
                .map(AuthorityBackendRegistrationRejectionReason::valueOf);
        return new AuthorityBackendRegistrationReceipt(
                AuthorityBackendRegistrationStatus.valueOf(required(fields, "status")),
                new CapabilityId(required(fields, "capabilityId")),
                required(fields, "descriptorDigest"),
                required(fields, "bundleDigest"),
                required(fields, "materializationPlanHash"),
                principalId,
                optional(fields, "grantFingerprint"),
                longValue(fields, "fencingEpoch"),
                Instant.parse(required(fields, "issuedAt")),
                required(fields, "receiptId"),
                rejectionReason,
                optional(fields, "artifactVerificationEvidence"),
                required(fields, "signature"));
    }

    private static Map<String, String> baseFields(String type) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("schema", SCHEMA);
        fields.put("type", type);
        return fields;
    }

    private static void requireSchema(Map<String, String> fields, String type) {
        if (!SCHEMA.equals(required(fields, "schema"))) {
            throw new IllegalArgumentException("unsupported authority registration wire schema");
        }
        if (!type.equals(required(fields, "type"))) {
            throw new IllegalArgumentException("expected authority registration wire type " + type);
        }
    }

    private static void putDescriptor(Map<String, String> fields, String prefix, CapabilityDescriptor descriptor) {
        fields.put(prefix + ".capabilityId", descriptor.capabilityId().value());
        fields.put(prefix + ".version", descriptor.version().value());
        putStrings(fields, prefix + ".requiredContracts", descriptor.requiredContracts().stream()
                .map(ContractName::value)
                .toList());
        putContracts(fields, prefix + ".declaredContracts", descriptor.declaredContracts());
        putAuthorities(fields, prefix + ".authorityDomains", descriptor.authorityDomains());
        putContributions(fields, prefix + ".contributions", descriptor.contributions());
        putStrings(fields, prefix + ".allowedScopes", descriptor.allowedScopes().stream()
                .map(CapabilityScope::value)
                .toList());
    }

    private static CapabilityDescriptor descriptor(Map<String, String> fields, String prefix) {
        return new CapabilityDescriptor(
                new CapabilityId(required(fields, prefix + ".capabilityId")),
                new CapabilityVersion(required(fields, prefix + ".version")),
                strings(fields, prefix + ".requiredContracts").stream().map(ContractName::new).toList(),
                contracts(fields, prefix + ".declaredContracts"),
                authorities(fields, prefix + ".authorityDomains"),
                contributions(fields, prefix + ".contributions"),
                strings(fields, prefix + ".allowedScopes").stream().map(CapabilityScope::new).toList());
    }

    private static void putContracts(Map<String, String> fields, String prefix, List<ContractDeclaration> contracts) {
        fields.put(prefix + ".count", Integer.toString(contracts.size()));
        for (int index = 0; index < contracts.size(); index++) {
            ContractDeclaration contract = contracts.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".name", contract.name().value());
            putCommands(fields, item + ".commands", contract.commands());
            putEvents(fields, item + ".events", contract.events());
            fields.put(item + ".snapshot.present", Boolean.toString(contract.snapshot().isPresent()));
            contract.snapshot().ifPresent(snapshot -> {
                fields.put(item + ".snapshot.payloadType", snapshot.payloadType());
                putFieldDeclarations(fields, item + ".snapshot.fields", snapshot.fields());
            });
            putProjections(fields, item + ".projections", contract.projections());
            putTopics(fields, item + ".topics", contract.topics());
            putAclRules(fields, item + ".aclRules", contract.aclRules());
        }
    }

    private static List<ContractDeclaration> contracts(Map<String, String> fields, String prefix) {
        List<ContractDeclaration> contracts = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            Optional<SnapshotDeclaration> snapshot = bool(fields, item + ".snapshot.present")
                    ? Optional.of(new SnapshotDeclaration(
                    required(fields, item + ".snapshot.payloadType"),
                    fieldDeclarations(fields, item + ".snapshot.fields")))
                    : Optional.empty();
            contracts.add(new ContractDeclaration(
                    new ContractName(required(fields, item + ".name")),
                    commands(fields, item + ".commands"),
                    events(fields, item + ".events"),
                    snapshot,
                    projections(fields, item + ".projections"),
                    topics(fields, item + ".topics"),
                    aclRules(fields, item + ".aclRules")));
        }
        return contracts;
    }

    private static void putCommands(Map<String, String> fields, String prefix, List<CommandDeclaration> commands) {
        fields.put(prefix + ".count", Integer.toString(commands.size()));
        for (int index = 0; index < commands.size(); index++) {
            CommandDeclaration command = commands.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".name", command.name().value());
            fields.put(item + ".payloadType", command.payloadType());
            fields.put(item + ".revisionGuarded", Boolean.toString(command.revisionGuarded()));
            putFieldDeclarations(fields, item + ".fields", command.fields());
        }
    }

    private static List<CommandDeclaration> commands(Map<String, String> fields, String prefix) {
        List<CommandDeclaration> commands = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            commands.add(new CommandDeclaration(
                    new CommandName(required(fields, item + ".name")),
                    required(fields, item + ".payloadType"),
                    fieldDeclarations(fields, item + ".fields"),
                    bool(fields, item + ".revisionGuarded")));
        }
        return commands;
    }

    private static void putEvents(Map<String, String> fields, String prefix, List<EventDeclaration> events) {
        fields.put(prefix + ".count", Integer.toString(events.size()));
        for (int index = 0; index < events.size(); index++) {
            EventDeclaration event = events.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".name", event.name().value());
            fields.put(item + ".payloadType", event.payloadType());
            putFieldDeclarations(fields, item + ".fields", event.fields());
        }
    }

    private static List<EventDeclaration> events(Map<String, String> fields, String prefix) {
        List<EventDeclaration> events = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            events.add(new EventDeclaration(
                    new EventName(required(fields, item + ".name")),
                    required(fields, item + ".payloadType"),
                    fieldDeclarations(fields, item + ".fields")));
        }
        return events;
    }

    private static void putProjections(Map<String, String> fields, String prefix, List<ProjectionDeclaration> projections) {
        fields.put(prefix + ".count", Integer.toString(projections.size()));
        for (int index = 0; index < projections.size(); index++) {
            ProjectionDeclaration projection = projections.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".name", projection.name());
            fields.put(item + ".relationName", projection.relationName());
            putFieldDeclarations(fields, item + ".fields", projection.fields());
        }
    }

    private static List<ProjectionDeclaration> projections(Map<String, String> fields, String prefix) {
        List<ProjectionDeclaration> projections = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            projections.add(new ProjectionDeclaration(
                    required(fields, item + ".name"),
                    required(fields, item + ".relationName"),
                    fieldDeclarations(fields, item + ".fields")));
        }
        return projections;
    }

    private static void putTopics(Map<String, String> fields, String prefix, List<TopicDeclaration> topics) {
        fields.put(prefix + ".count", Integer.toString(topics.size()));
        for (int index = 0; index < topics.size(); index++) {
            TopicDeclaration topic = topics.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".name", topic.name());
            fields.put(item + ".family", topic.family().name());
        }
    }

    private static List<TopicDeclaration> topics(Map<String, String> fields, String prefix) {
        List<TopicDeclaration> topics = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            topics.add(new TopicDeclaration(
                    required(fields, item + ".name"),
                    TopicFamily.valueOf(required(fields, item + ".family"))));
        }
        return topics;
    }

    private static void putAclRules(Map<String, String> fields, String prefix, List<AclRuleDeclaration> rules) {
        fields.put(prefix + ".count", Integer.toString(rules.size()));
        for (int index = 0; index < rules.size(); index++) {
            AclRuleDeclaration rule = rules.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".resource", rule.resource());
            putStrings(fields, item + ".producers", rule.producers());
            putStrings(fields, item + ".consumers", rule.consumers());
        }
    }

    private static List<AclRuleDeclaration> aclRules(Map<String, String> fields, String prefix) {
        List<AclRuleDeclaration> rules = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            rules.add(new AclRuleDeclaration(
                    required(fields, item + ".resource"),
                    strings(fields, item + ".producers"),
                    strings(fields, item + ".consumers")));
        }
        return rules;
    }

    private static void putFieldDeclarations(Map<String, String> fields, String prefix, List<FieldDeclaration> declarations) {
        fields.put(prefix + ".count", Integer.toString(declarations.size()));
        for (int index = 0; index < declarations.size(); index++) {
            FieldDeclaration declaration = declarations.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".name", declaration.name());
            fields.put(item + ".type", declaration.type().name());
            fields.put(item + ".nullable", Boolean.toString(declaration.nullable()));
        }
    }

    private static List<FieldDeclaration> fieldDeclarations(Map<String, String> fields, String prefix) {
        List<FieldDeclaration> declarations = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            declarations.add(new FieldDeclaration(
                    required(fields, item + ".name"),
                    FieldType.valueOf(required(fields, item + ".type")),
                    bool(fields, item + ".nullable")));
        }
        return declarations;
    }

    private static void putAuthorities(
            Map<String, String> fields,
            String prefix,
            List<CapabilityAuthorityDeclaration> authorities) {
        fields.put(prefix + ".count", Integer.toString(authorities.size()));
        for (int index = 0; index < authorities.size(); index++) {
            CapabilityAuthorityDeclaration authority = authorities.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".authorityDomain", authority.authorityDomain());
            fields.put(item + ".resourceClass", authority.resourceClass());
            fields.put(item + ".partitions", Integer.toString(authority.partitions()));
        }
    }

    private static List<CapabilityAuthorityDeclaration> authorities(Map<String, String> fields, String prefix) {
        List<CapabilityAuthorityDeclaration> authorities = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            authorities.add(new CapabilityAuthorityDeclaration(
                    required(fields, item + ".authorityDomain"),
                    required(fields, item + ".resourceClass"),
                    intValue(fields, item + ".partitions")));
        }
        return authorities;
    }

    private static void putContributions(
            Map<String, String> fields,
            String prefix,
            List<ContributionDeclaration> contributions) {
        fields.put(prefix + ".count", Integer.toString(contributions.size()));
        for (int index = 0; index < contributions.size(); index++) {
            ContributionDeclaration contribution = contributions.get(index);
            String item = prefix + "." + index;
            fields.put(item + ".extensionPoint", contribution.extensionPoint().wireName());
            fields.put(item + ".scope", contribution.scope().value());
            fields.put(item + ".order", Integer.toString(contribution.order()));
        }
    }

    private static List<ContributionDeclaration> contributions(Map<String, String> fields, String prefix) {
        List<ContributionDeclaration> contributions = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            String item = prefix + "." + index;
            contributions.add(new ContributionDeclaration(
                    extensionPoint(required(fields, item + ".extensionPoint")),
                    new CapabilityScope(required(fields, item + ".scope")),
                    intValue(fields, item + ".order")));
        }
        return contributions;
    }

    private static CapabilityExtensionPoint extensionPoint(String value) {
        for (CapabilityExtensionPoint extensionPoint : CapabilityExtensionPoint.values()) {
            if (extensionPoint.wireName().equals(value) || extensionPoint.name().equals(value)) {
                return extensionPoint;
            }
        }
        throw new IllegalArgumentException("unknown capability extension point: " + value);
    }

    private static void putSecurityContext(
            Map<String, String> fields,
            Optional<HostSecurityContext> securityContext) {
        fields.put("security.present", Boolean.toString(securityContext.isPresent()));
        securityContext.ifPresent(context -> {
            fields.put("security.identity.instanceId", context.identity().instanceId().value());
            fields.put("security.identity.instanceKind", context.identity().instanceKind());
            fields.put("security.identity.poolId", context.identity().poolId().value());
            fields.put("security.identity.machineRef", context.identity().machineRef().value());
            fields.put("security.identity.principalId", context.identity().principalId().value());
            fields.put("security.credentialRef", context.credentialRef());
            List<HostResourceGrant> grants = context.credentialScope().grants().stream()
                    .sorted(Comparator
                            .comparing((HostResourceGrant grant) -> grant.resourceFamily().name())
                            .thenComparing(grant -> grant.accessMode().name())
                            .thenComparing(HostResourceGrant::resourceName))
                    .toList();
            fields.put("security.grants.count", Integer.toString(grants.size()));
            for (int index = 0; index < grants.size(); index++) {
                HostResourceGrant grant = grants.get(index);
                String item = "security.grants." + index;
                fields.put(item + ".resourceFamily", grant.resourceFamily().name());
                fields.put(item + ".accessMode", grant.accessMode().name());
                fields.put(item + ".resourceName", grant.resourceName());
            }
        });
    }

    private static Optional<HostSecurityContext> securityContext(Map<String, String> fields) {
        if (!bool(fields, "security.present")) {
            return Optional.empty();
        }
        Set<HostResourceGrant> grants = new LinkedHashSet<>();
        for (int index = 0; index < count(fields, "security.grants"); index++) {
            String item = "security.grants." + index;
            grants.add(new HostResourceGrant(
                    HostResourceFamily.valueOf(required(fields, item + ".resourceFamily")),
                    HostAccessMode.valueOf(required(fields, item + ".accessMode")),
                    required(fields, item + ".resourceName")));
        }
        return Optional.of(new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId(required(fields, "security.identity.instanceId")),
                        required(fields, "security.identity.instanceKind"),
                        new PoolId(required(fields, "security.identity.poolId")),
                        new MachineRef(required(fields, "security.identity.machineRef")),
                        new PrincipalId(required(fields, "security.identity.principalId"))),
                required(fields, "security.credentialRef"),
                new HostCredentialScope(grants)));
    }

    private static void putArtifactVerification(
            Map<String, String> fields,
            Optional<AuthorityArtifactVerificationEvidence> artifactVerification) {
        fields.put("artifactVerification.present", Boolean.toString(artifactVerification.isPresent()));
        artifactVerification.ifPresent(evidence -> {
            fields.put("artifactVerification.verified", Boolean.toString(evidence.verified()));
            fields.put("artifactVerification.sourceKind", evidence.sourceKind());
            fields.put("artifactVerification.sourceReference", evidence.sourceReference());
            fields.put("artifactVerification.digest", evidence.digest());
            fields.put("artifactVerification.evidence", evidence.evidence());
        });
    }

    private static Optional<AuthorityArtifactVerificationEvidence> artifactVerification(Map<String, String> fields) {
        if (!bool(fields, "artifactVerification.present")) {
            return Optional.empty();
        }
        return Optional.of(new AuthorityArtifactVerificationEvidence(
                bool(fields, "artifactVerification.verified"),
                required(fields, "artifactVerification.sourceKind"),
                required(fields, "artifactVerification.sourceReference"),
                required(fields, "artifactVerification.digest"),
                required(fields, "artifactVerification.evidence")));
    }

    private static void putStrings(Map<String, String> fields, String prefix, List<String> values) {
        fields.put(prefix + ".count", Integer.toString(values.size()));
        for (int index = 0; index < values.size(); index++) {
            fields.put(prefix + "." + index, values.get(index));
        }
    }

    private static List<String> strings(Map<String, String> fields, String prefix) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count(fields, prefix); index++) {
            values.add(required(fields, prefix + "." + index));
        }
        return values;
    }

    private static void putOptional(Map<String, String> fields, String name, Optional<String> value) {
        fields.put(name + ".present", Boolean.toString(value.isPresent()));
        value.ifPresent(actual -> fields.put(name, actual));
    }

    private static Optional<String> optional(Map<String, String> fields, String name) {
        return bool(fields, name + ".present") ? Optional.of(required(fields, name)) : Optional.empty();
    }

    private static String encode(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("\n"));
    }

    private static Map<String, String> decode(String wireValue) {
        String checked = AuthoritySdkNames.requireNonBlank(wireValue, "wireValue");
        Map<String, String> fields = new TreeMap<>();
        for (String line : checked.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("malformed authority registration wire line");
            }
            String key = line.substring(0, separator);
            String value = URLDecoder.decode(line.substring(separator + 1), StandardCharsets.UTF_8);
            if (fields.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate authority registration wire field: " + key);
            }
        }
        return fields;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing authority registration wire field: " + key);
        }
        return value;
    }

    private static int count(Map<String, String> fields, String prefix) {
        return intValue(fields, prefix + ".count");
    }

    private static int intValue(Map<String, String> fields, String key) {
        int value = Integer.parseInt(required(fields, key));
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be non-negative");
        }
        return value;
    }

    private static long longValue(Map<String, String> fields, String key) {
        long value = Long.parseLong(required(fields, key));
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be non-negative");
        }
        return value;
    }

    private static boolean bool(Map<String, String> fields, String key) {
        String value = required(fields, key);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
