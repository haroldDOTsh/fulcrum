package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDeregistrationRequest;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDeregistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDeregistrationStatus;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationClient;

import java.time.Instant;
import java.util.Optional;

final class RenderedBundleInstanceSupervisor implements BundleInstanceSupervisor {
    private static final String AUTHORITY_BACKEND_KIND = "authority-backend";

    private final BundleInstanceStateStore stateStore;
    private final BundleInstanceArtifactRenderer artifactRenderer;
    private final BundleRuntimeAdapter runtimeAdapter;
    private final BundleReadinessObserver readinessObserver;
    private final AuthorityBackendRegistrationClient registrationClient;

    RenderedBundleInstanceSupervisor(
            BundleInstanceStateStore stateStore,
            BundleInstanceArtifactRenderer artifactRenderer,
            BundleRuntimeAdapter runtimeAdapter,
            BundleReadinessObserver readinessObserver,
            AuthorityBackendRegistrationClient registrationClient) {
        this.stateStore = java.util.Objects.requireNonNull(stateStore, "stateStore");
        this.artifactRenderer = java.util.Objects.requireNonNull(artifactRenderer, "artifactRenderer");
        this.runtimeAdapter = java.util.Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        this.readinessObserver = java.util.Objects.requireNonNull(readinessObserver, "readinessObserver");
        this.registrationClient = java.util.Objects.requireNonNull(registrationClient, "registrationClient");
    }

    @Override
    public BundleInstanceStartReceipt start(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            AuthorityArtifactVerificationEvidence artifactVerification,
            Instant now) {
        if (!bundle.kind().equals(AUTHORITY_BACKEND_KIND)) {
            return BundleInstanceStartReceipt.refused("UNSUPPORTED_STATEFUL_BUNDLE_KIND");
        }
        if (!supportedPlacementProfile(bundle.placementProfile())) {
            return BundleInstanceStartReceipt.refused("UNSUPPORTED_BUNDLE_PLACEMENT_PROFILE");
        }
        if (!(bundle.resourceClasses().size() == 1 || bundle.resourceClasses().size() == bundle.authorityDomains().size())) {
            return BundleInstanceStartReceipt.refused("AUTHORITY_RESOURCE_CLASS_CARDINALITY_MISMATCH");
        }
        BundleInstanceManifest manifest = BundleInstanceManifest.from(bundle, grant, artifactVerification);
        BundleRenderedInstance rendered = artifactRenderer.describe(manifest);
        BundleInstanceRecord latest = stateStore.latestByBundle().get(bundle.id());
        if (sameRenderedInstance(latest, bundle, rendered)) {
            if ((latest.status().equals("RUNNING") || latest.status().equals("STARTED"))
                    && latest.launchNonce().isPresent()
                    && latest.runtimeEvidence().isPresent()) {
                BundleInstanceStartReceipt observed = observeRecordedRuntime(latest, rendered, manifest, now);
                stateStore.append(BundleInstanceRecord.started(bundle, grant, observed, now));
                return observed;
            }
        }

        String launchNonce = BundleLaunchNonces.generate();
        rendered = artifactRenderer.render(manifest, launchNonce);
        if (!bundle.placementProfile().equals(DeploymentProfile.SINGLE_MACHINE.id())) {
            BundleInstanceStartReceipt receipt = BundleInstanceStartReceipt.rendered(
                    manifest.instanceId(),
                    rendered.manifestHash(),
                    rendered.manifestHash(),
                    rendered.manifestFile().toString(),
                    launchNonce);
            stateStore.append(BundleInstanceRecord.started(bundle, grant, receipt, now));
            return receipt;
        }
        BundleRuntimeStartReceipt runtime = runtimeAdapter.start(rendered, manifest, now);
        BundleInstanceStartReceipt receipt;
        if (runtime.renderedOnly()) {
            receipt = BundleInstanceStartReceipt.rendered(
                    manifest.instanceId(),
                    rendered.manifestHash(),
                    rendered.manifestHash(),
                    rendered.manifestFile().toString(),
                    launchNonce);
        } else if (runtime.started()) {
            BundleReadinessReceipt readiness = readinessObserver.observe(rendered, manifest, launchNonce, now);
            if (readiness.ready()) {
                receipt = BundleInstanceStartReceipt.running(
                        manifest.instanceId(),
                        rendered.manifestHash(),
                        rendered.manifestHash(),
                        rendered.manifestFile().toString(),
                        launchNonce,
                        runtime.runtimeEvidence().orElseThrow(),
                        readiness.registrationReceiptId().orElseThrow(),
                        readiness.registrationEvidence().orElseThrow());
            } else if (readiness.rejected()) {
                receipt = BundleInstanceStartReceipt.startFailed(
                        readiness.reason(),
                        manifest.instanceId(),
                        rendered.manifestHash(),
                        rendered.manifestHash(),
                        rendered.manifestFile().toString(),
                        launchNonce,
                        runtime.runtimeEvidence().orElseThrow()
                                + "|readinessEvidenceDigest="
                                + AuthorityBackendDescriptorDigests.sha256Hex(readiness.registrationEvidence().orElseThrow()));
            } else {
                receipt = BundleInstanceStartReceipt.started(
                        manifest.instanceId(),
                        rendered.manifestHash(),
                        rendered.manifestHash(),
                        rendered.manifestFile().toString(),
                        launchNonce,
                        runtime.runtimeEvidence().orElseThrow());
            }
        } else if (runtime.failed()) {
            receipt = BundleInstanceStartReceipt.startFailed(
                    runtime.reason(),
                    manifest.instanceId(),
                    rendered.manifestHash(),
                    rendered.manifestHash(),
                    rendered.manifestFile().toString(),
                    launchNonce,
                    runtime.runtimeEvidence().orElseThrow());
        } else {
            receipt = BundleInstanceStartReceipt.refused(
                    "UNSUPPORTED_RUNTIME_START_STATUS",
                    manifest.instanceId(),
                    rendered.manifestHash(),
                    rendered.manifestHash(),
                    rendered.manifestFile().toString(),
                    launchNonce);
        }
        stateStore.append(BundleInstanceRecord.started(bundle, grant, receipt, now));
        return receipt;
    }

    private BundleInstanceStartReceipt observeRecordedRuntime(
            BundleInstanceRecord latest,
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            Instant now) {
        BundleRuntimeObservation runtime = runtimeAdapter.observe(latest, now);
        if (!(runtime.status().equals("RUNNING") || runtime.status().equals("STARTED"))) {
            return BundleInstanceStartReceipt.startFailed(
                    runtime.reason(),
                    latest.instanceId().orElseThrow(),
                    latest.shapeFingerprint().orElseThrow(),
                    latest.manifestHash().orElseThrow(),
                    latest.manifestPath().orElseThrow(),
                    latest.launchNonce().orElseThrow(),
                    runtimeEvidenceForFailure(runtime, latest));
        }
        if (runtime.runtimeEvidence().isEmpty()) {
            return BundleInstanceStartReceipt.startFailed(
                    "runtime-running-observation-missing-evidence",
                    latest.instanceId().orElseThrow(),
                    latest.shapeFingerprint().orElseThrow(),
                    latest.manifestHash().orElseThrow(),
                    latest.manifestPath().orElseThrow(),
                    latest.launchNonce().orElseThrow(),
                    runtimeEvidenceForFailure(runtime, latest));
        }
        String runtimeEvidence = runtime.runtimeEvidence().orElseThrow();
        String launchNonce = latest.launchNonce().orElseThrow();
        BundleReadinessReceipt readiness = readinessObserver.observe(rendered, manifest, launchNonce, now);
        if (readiness.ready()) {
            return BundleInstanceStartReceipt.running(
                    latest.status().equals("RUNNING")
                            ? "instance-observed-running-and-self-registered"
                            : "instance-observed-self-registered",
                    latest.instanceId().orElseThrow(),
                    latest.shapeFingerprint().orElseThrow(),
                    latest.manifestHash().orElseThrow(),
                    latest.manifestPath().orElseThrow(),
                    launchNonce,
                    runtimeEvidence,
                    readiness.registrationReceiptId().orElseThrow(),
                    readiness.registrationEvidence().orElseThrow());
        }
        if (readiness.rejected()) {
            return BundleInstanceStartReceipt.startFailed(
                    readiness.reason(),
                    latest.instanceId().orElseThrow(),
                    latest.shapeFingerprint().orElseThrow(),
                    latest.manifestHash().orElseThrow(),
                    latest.manifestPath().orElseThrow(),
                    launchNonce,
                    runtimeEvidence
                            + "|readinessEvidenceDigest="
                            + AuthorityBackendDescriptorDigests.sha256Hex(readiness.registrationEvidence().orElseThrow()));
        }
        return BundleInstanceStartReceipt.started(
                latest.instanceId().orElseThrow(),
                latest.shapeFingerprint().orElseThrow(),
                latest.manifestHash().orElseThrow(),
                latest.manifestPath().orElseThrow(),
                launchNonce,
                runtimeEvidence);
    }

    private static String runtimeEvidenceForFailure(
            BundleRuntimeObservation runtime,
            BundleInstanceRecord latest) {
        return runtime.runtimeEvidence().orElseGet(() -> "runtime=unobserved"
                + "|bundleId=" + evidenceValue(latest.bundleId())
                + "|instanceId=" + evidenceValue(latest.instanceId().orElse("none"))
                + "|observationStatus=" + evidenceValue(runtime.status())
                + "|reason=" + evidenceValue(runtime.reason()));
    }

    private static String evidenceValue(String value) {
        return java.util.Objects.requireNonNull(value, "value")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("|", "/");
    }

    private static boolean sameRenderedInstance(
            BundleInstanceRecord latest,
            DeclaredBundle bundle,
            BundleRenderedInstance rendered) {
        return latest != null
                && latest.digest().equals(bundle.digest())
                && latest.manifestHash().filter(rendered.manifestHash()::equals).isPresent()
                && latest.manifestPath().filter(rendered.manifestFile().toString()::equals).isPresent()
                && latest.instanceId().isPresent()
                && !latest.status().equals("REMOVED");
    }

    private static boolean supportedPlacementProfile(String placementProfile) {
        return placementProfile.equals(DeploymentProfile.SINGLE_MACHINE.id())
                || placementProfile.equals(DeploymentProfile.SMALL_PRODUCTION.id())
                || placementProfile.equals(DeploymentProfile.LARGE_PRODUCTION.id());
    }

    @Override
    public BundleInstanceRemovalReceipt remove(String bundleId, Instant now) {
        BundleInstanceRecord latest = stateStore.latestByBundle().get(bundleId);
        if (latest == null || latest.status().equals("REMOVED")) {
            return BundleInstanceRemovalReceipt.removed(
                    Optional.empty(),
                    Optional.empty(),
                    "instance-already-absent");
        }
        if (latest.registrationReceiptId().isEmpty() && latest.status().equals("RENDERED")) {
            stateStore.append(latest.removed(now));
            return BundleInstanceRemovalReceipt.removed(
                    latest.instanceId(),
                    Optional.empty(),
                    "rendered-instance-removed-grant-revoked");
        }
        if (latest.registrationReceiptId().isEmpty()) {
            BundleRuntimeStopReceipt stop = runtimeAdapter.stop(latest, now);
            if (!stop.stoppedOrSkipped()) {
                return BundleInstanceRemovalReceipt.blocked(
                        latest.instanceId(),
                        Optional.empty(),
                        stop.reason());
            }
            stateStore.append(latest.removed(now));
            return BundleInstanceRemovalReceipt.removed(
                    latest.instanceId(),
                    Optional.empty(),
                    "runtime-stopped-grant-revoked");
        }
        AuthorityBackendDeregistrationReceipt deregistration = registrationClient.deregister(
                new AuthorityBackendDeregistrationRequest(
                        latest.registrationReceiptId().orElseThrow(),
                        Optional.empty(),
                        "bundle-declaration-removed",
                        now));
        if (!deregistration.tombstoned() && deregistration.status() != AuthorityBackendDeregistrationStatus.NOT_FOUND) {
            return BundleInstanceRemovalReceipt.blocked(
                    latest.instanceId(),
                    latest.registrationReceiptId(),
                    "registration-" + deregistration.status().name());
        }
        BundleRuntimeStopReceipt stop = runtimeAdapter.stop(latest, now);
        if (!stop.stoppedOrSkipped()) {
            return BundleInstanceRemovalReceipt.blocked(
                    latest.instanceId(),
                    latest.registrationReceiptId(),
                    stop.reason());
        }
        stateStore.append(latest.removed(now));
        return BundleInstanceRemovalReceipt.removed(
                latest.instanceId(),
                latest.registrationReceiptId(),
                deregistration.tombstoned()
                        ? "registration-tombstoned-instance-stopped-grant-revoked"
                        : "registration-already-absent-instance-stopped-grant-revoked");
    }

}
