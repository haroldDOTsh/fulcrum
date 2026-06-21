package sh.harold.fulcrum.sdk.authoring;

import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthoritySdkVersion;

import java.util.Map;
import java.util.Objects;

public final class AuthorBundleScaffold {
    private AuthorBundleScaffold() {
    }

    public static GeneratedAuthorBundle contribution(AuthorBundleScaffoldRequest request) {
        Objects.requireNonNull(request, "request");
        String packagePath = request.packageName().replace('.', '/');
        String providerClassName = request.providerClassName();
        String providerSource = """
                package %s;

                import sh.harold.fulcrum.sdk.authoring.AuthorContributionProbe;
                import sh.harold.fulcrum.sdk.authority.AuthoritySdkVersion;

                public final class %s implements AuthorContributionProbe {
                    @Override
                    public String probe() {
                        return "%s loaded with SDK " + AuthoritySdkVersion.CURRENT;
                    }
                }
                """.formatted(request.packageName(), request.providerSimpleName(), request.bundleId());
        return new GeneratedAuthorBundle(
                request.bundleId(),
                providerClassName,
                request.descriptor(),
                request.substrateFingerprint(),
                Map.of(
                        "build.gradle.kts", buildFile(),
                        "src/main/java/" + packagePath + "/" + request.providerSimpleName() + ".java", providerSource,
                        "src/main/resources/META-INF/services/sh.harold.fulcrum.sdk.authoring.AuthorContributionProbe", providerClassName + "\n",
                        "src/main/resources/META-INF/fulcrum/authoring.properties", authoringProperties(request, "contribution"),
                        "src/main/resources/META-INF/fulcrum/bundle.properties", bundleProperties(request, "contribution"),
                        "src/test/java/" + packagePath + "/" + request.providerSimpleName() + "Smoke.java", smokeTest(request)));
    }

    public static GeneratedAuthorBundle authority(AuthorBundleScaffoldRequest request) {
        Objects.requireNonNull(request, "request");
        String packagePath = request.packageName().replace('.', '/');
        String providerClassName = request.providerClassName();
        String providerSource = """
                package %s;

                import sh.harold.fulcrum.sdk.authority.AuthoritySdkVersion;

                public final class %s {
                    public String startupMode() {
                        return "%s authority backend loaded with SDK " + AuthoritySdkVersion.CURRENT;
                    }
                }
                """.formatted(request.packageName(), request.providerSimpleName(), request.bundleId());
        return new GeneratedAuthorBundle(
                request.bundleId(),
                providerClassName,
                request.descriptor(),
                request.substrateFingerprint(),
                Map.of(
                        "build.gradle.kts", buildFile(),
                        "src/main/java/" + packagePath + "/" + request.providerSimpleName() + ".java", providerSource,
                        "src/main/resources/META-INF/fulcrum/authoring.properties", authoringProperties(request, "authority"),
                        "src/main/resources/META-INF/fulcrum/bundle.properties", bundleProperties(request, "authority"),
                        "src/test/java/" + packagePath + "/" + request.providerSimpleName() + "Smoke.java", smokeTest(request)));
    }

    private static String buildFile() {
        return """
                plugins {
                    `java-library`
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("sh.harold.fulcrum:fulcrum-sdk-bom:%s"))
                    api("sh.harold.fulcrum:authoring-sdk")
                    api("sh.harold.fulcrum:authority-sdk")
                }
                """.formatted(AuthoritySdkVersion.CURRENT);
    }

    private static String authoringProperties(AuthorBundleScaffoldRequest request, String kind) {
        return """
                substrate.fingerprint=%s
                sdk.coordinate=sh.harold.fulcrum:authoring-sdk:%s
                authority.sdk.coordinate=sh.harold.fulcrum:authority-sdk:%s
                bundle.id=%s
                bundle.kind=%s
                provider.class=%s
                """.formatted(
                request.substrateFingerprint(),
                AuthoritySdkVersion.CURRENT,
                AuthoritySdkVersion.CURRENT,
                request.bundleId(),
                kind,
                request.providerClassName());
    }

    private static String bundleProperties(AuthorBundleScaffoldRequest request, String kind) {
        String contributions = request.descriptor().contributions().stream()
                .map(contribution -> contribution.extensionPoint().wireName()
                        + ":" + contribution.scope().value()
                        + ":" + contribution.order())
                .collect(java.util.stream.Collectors.joining(","));
        String authorities = request.descriptor().authorityDomains().stream()
                .map(authority -> authority.authorityDomain()
                        + ":" + authority.resourceClass()
                        + ":" + authority.partitions())
                .collect(java.util.stream.Collectors.joining(","));
        return """
                bundle.id=%s
                bundle.kind=%s
                descriptor.digest=%s
                bundle.digest=declared-by-artifact-pin
                providers=%s
                contributions=%s
                authorities=%s
                """.formatted(
                request.bundleId(),
                kind,
                AuthorityBackendDescriptorDigests.descriptorDigest(request.descriptor()),
                request.providerClassName(),
                contributions,
                authorities);
    }

    private static String smokeTest(AuthorBundleScaffoldRequest request) {
        return """
                package %s;

                public final class %sSmoke {
                    public static void main(String[] args) {
                        new %s();
                    }
                }
                """.formatted(request.packageName(), request.providerSimpleName(), request.providerSimpleName());
    }
}
