package sh.harold.fulcrum.validation.auctionexperience;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.capability.api.CapabilityDescriptor;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityVersion;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.capability.bundle.BundleLoadStatus;
import sh.harold.fulcrum.capability.bundle.BundleLoadStep;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.host.api.HostMenuClickRequest;
import sh.harold.fulcrum.host.api.HostMenuContribution;
import sh.harold.fulcrum.host.api.HostMenuOpenRequest;
import sh.harold.fulcrum.host.api.HostMenuReceipt;
import sh.harold.fulcrum.host.api.HostMenuRenderFrame;
import sh.harold.fulcrum.host.api.HostMenuSlot;
import sh.harold.fulcrum.host.paper.PaperContributionBundleBootstrap;
import sh.harold.fulcrum.host.paper.PaperContributionBundleDeclaration;
import sh.harold.fulcrum.host.paper.PaperContributionLoadReceipt;
import sh.harold.fulcrum.host.paper.PaperLoadedContribution;
import sh.harold.fulcrum.validation.auctionescrow.AuctionEscrowContract;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuctionPaperMenuContributionBundleTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final String BUCKET = "artifact-store";
    private static final String PROVIDER = "external.auctionpaper.AuctionPaperContributionProvider";
    private static final String DESCRIPTOR_DIGEST = sha256("auction-paper-menu-descriptor".getBytes(StandardCharsets.UTF_8));
    private static final ContributionDeclaration PAPER_MENU =
            new ContributionDeclaration(CapabilityExtensionPoint.PAPER_MENUS, CapabilityScope.NETWORK, 0);

    @Test
    void paperBootstrapLoadsAuctionMenuContributionAndReplaysFakeHostClicks(@TempDir Path tempDir)
            throws Exception {
        byte[] jarBytes = providerJar(tempDir, DESCRIPTOR_DIGEST);
        ArtifactPin pin = new ArtifactPin(new ArtifactId("artifact.auction-paper-menu"), sha256(jarBytes), "fulcrum-bundle-v1");
        PaperContributionBundleBootstrap bootstrap = bootstrap(tempDir, pin, jarBytes);

        assertThrows(ClassNotFoundException.class, () ->
                Class.forName(PROVIDER, false, AuctionPaperMenuContributionBundleTest.class.getClassLoader()));

        try (PaperLoadedContribution<HostMenuContribution> contribution =
                     bootstrap.load(declaration(pin), HostMenuContribution.class)) {
            PaperContributionLoadReceipt receipt = contribution.receipt();
            assertEquals(BundleLoadStatus.LOADED, receipt.status());
            assertEquals("paper-agent:auction-menu-test", receipt.hostIdentity());
            assertEquals(Optional.of("auction-paper-menu"), receipt.bundleId());
            assertEquals(Optional.of(PROVIDER), receipt.providerClassName());
            assertTrue(receipt.steps().contains(BundleLoadStep.PROVIDER_LOADED));
            assertEquals(receipt.cachedPath().orElseThrow().toUri(), URI.create(receipt.providerCodeSource().orElseThrow()));
            assertNotEquals(
                    AuctionPaperMenuContributionBundleTest.class.getClassLoader(),
                    contribution.provider().getClass().getClassLoader());

            HostMenuContribution menu = contribution.provider();
            HostMenuRenderFrame listing = menu.open(new HostMenuOpenRequest(
                    "seller",
                    "paper-session",
                    "/ah sell auction-alpha beacon COIN",
                    "open-menu",
                    NOW));

            assertEquals("auction:auction-alpha", listing.menuId());
            assertEquals("Confirm Auction Listing", listing.title());
            assertTrue(listing.receipts().isEmpty());
            HostMenuSlot confirm = actionSlot(listing, "CONFIRM_LISTING");
            assertEquals("beacon", confirm.attributes().get("itemRef"));
            assertEquals("COIN", confirm.attributes().get("currency"));

            HostMenuRenderFrame opened = menu.click(new HostMenuClickRequest(
                    "seller",
                    "paper-session",
                    listing.menuId(),
                    confirm.actionId().orElseThrow(),
                    confirm.attributes(),
                    "confirm-open",
                    NOW.plusSeconds(1)));
            assertReceipt(opened, AuctionEscrowContract.OPEN.value(), "paper-session:confirm-open");

            HostMenuSlot bid = actionSlot(opened, "PLACE_BID");
            Map<String, String> bidAttributes = new LinkedHashMap<>(bid.attributes());
            bidAttributes.put("amountMinor", "250");
            HostMenuRenderFrame bidSubmitted = menu.click(new HostMenuClickRequest(
                    "bidder-high",
                    "paper-session",
                    opened.menuId(),
                    bid.actionId().orElseThrow(),
                    bidAttributes,
                    "bid-250",
                    NOW.plusSeconds(2)));
            assertReceipt(bidSubmitted, AuctionEscrowContract.HOLD.value(), "paper-session:bid-250");

            HostMenuSlot settle = actionSlot(bidSubmitted, "SETTLE");
            HostMenuRenderFrame settled = menu.click(new HostMenuClickRequest(
                    "seller",
                    "paper-session",
                    bidSubmitted.menuId(),
                    settle.actionId().orElseThrow(),
                    settle.attributes(),
                    "settle",
                    NOW.plusSeconds(3)));
            assertReceipt(settled, AuctionEscrowContract.SETTLE.value(), "paper-session:settle");
            assertFalse(settled.refusalReason().isPresent());
        }
    }

    private static void assertReceipt(HostMenuRenderFrame frame, String commandName, String idempotencyKey) {
        assertEquals("Auction Board", frame.title());
        assertEquals(1, frame.receipts().size());
        HostMenuReceipt receipt = frame.receipts().getFirst();
        assertEquals(AuctionEscrowContract.CONTRACT, receipt.contractName());
        assertEquals(commandName, receipt.commandName().value());
        assertEquals(idempotencyKey, receipt.idempotencyKey());
    }

    private static HostMenuSlot actionSlot(HostMenuRenderFrame frame, String actionId) {
        return frame.slots().stream()
                .filter(slot -> slot.actionId().map(actionId::equals).orElse(false))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing action slot " + actionId + " in " + frame.slots()));
    }

    private static PaperContributionBundleBootstrap bootstrap(Path tempDir, ArtifactPin pin, byte[] jarBytes) {
        Map<String, byte[]> artifacts = Map.of(pin.artifactId().value(), jarBytes);
        return new PaperContributionBundleBootstrap(
                "paper-agent:auction-menu-test",
                BUCKET,
                tempDir.resolve("cache"),
                address -> Optional.ofNullable(artifacts.get(pin.artifactId().value())));
    }

    private static PaperContributionBundleDeclaration declaration(ArtifactPin pin) {
        return new PaperContributionBundleDeclaration(
                pin,
                DESCRIPTOR_DIGEST,
                CapabilityMaterializationPlanner.plan(List.of(descriptor())));
    }

    private static CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                new CapabilityId("auction-paper-menu"),
                new CapabilityVersion("0.0.1"),
                List.of(),
                List.of(),
                List.of(),
                List.of(PAPER_MENU),
                List.of(CapabilityScope.NETWORK));
    }

    private static byte[] providerJar(Path tempDir, String descriptorDigest) throws IOException {
        Path classesDir = compileProvider(tempDir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            addEntry(jar, "META-INF/fulcrum/bundle.properties", """
                    bundle.id=auction-paper-menu
                    descriptor.digest=%s
                    bundle.digest=declared-by-artifact-pin
                    providers=%s
                    contributions=Paper.Menus:network:0
                    """.formatted(descriptorDigest, PROVIDER).getBytes(StandardCharsets.UTF_8));
            addEntry(jar, "META-INF/services/sh.harold.fulcrum.host.api.HostMenuContribution",
                    PROVIDER.getBytes(StandardCharsets.UTF_8));
            try (var files = Files.walk(classesDir)) {
                for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                    addEntry(jar, classesDir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static Path compileProvider(Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("provider-source");
        Path classesDir = tempDir.resolve("provider-classes");
        Path source = sourceRoot.resolve(PROVIDER.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDir);
        Files.writeString(source, """
                package external.auctionpaper;

                import sh.harold.fulcrum.validation.auctionexperience.AuctionPaperMenuContribution;

                public final class AuctionPaperContributionProvider extends AuctionPaperMenuContribution {
                    public AuctionPaperContributionProvider() {
                        super();
                    }
                }
                """, StandardCharsets.UTF_8);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for auction Paper contribution validation");
        try (var fileManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var compilationUnits = fileManager.getJavaFileObjectsFromPaths(List.of(source));
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

    private static void addEntry(JarOutputStream jar, String entryName, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(entryName));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
