package sh.harold.fulcrum.sdk.authoring;

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
        String buildFile = """
                plugins {
                    `java-library`
                }

                dependencies {
                    api("sh.harold.fulcrum:sdk-authority-sdk:%s")
                }
                """.formatted(AuthoritySdkVersion.CURRENT);
        String fingerprint = """
                substrate.fingerprint=%s
                sdk.coordinate=sh.harold.fulcrum:sdk-authority-sdk:%s
                bundle.id=%s
                """.formatted(request.substrateFingerprint(), AuthoritySdkVersion.CURRENT, request.bundleId());
        return new GeneratedAuthorBundle(
                request.bundleId(),
                providerClassName,
                request.descriptor(),
                request.substrateFingerprint(),
                Map.of(
                        "build.gradle.kts", buildFile,
                        "src/main/java/" + packagePath + "/" + request.providerSimpleName() + ".java", providerSource,
                        "src/main/resources/META-INF/services/sh.harold.fulcrum.sdk.authoring.AuthorContributionProbe", providerClassName + "\n",
                        "src/main/resources/META-INF/fulcrum/authoring.properties", fingerprint));
    }
}
