package dev.creas.uuidrestorer;

import com.google.gson.Gson;
import dev.creas.uuidrestorer.config.UuidRestorerConfig;
import dev.creas.uuidrestorer.data.BindingStore;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttempt;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttemptStatus;
import dev.creas.uuidrestorer.runtime.AuthlibCompat;
import dev.creas.uuidrestorer.runtime.ResolvedProfile;
import dev.creas.uuidrestorer.runtime.ServerAccess;
import dev.creas.uuidrestorer.service.LoginDecision;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionPreference;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionScope;
import net.minecraft.util.Uuids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidRestorerControllerTest {
    private final Gson gson = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void reloadCreatesDefaultConfig() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);

        controller.reload();

        assertTrue(Files.exists(tempDir.resolve("config.json")));
        assertTrue(Files.exists(tempDir.resolve("bindings.json")));
        assertEquals(UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO, controller.config().bindingMode);
        assertTrue(Boolean.TRUE.equals(controller.config().allowOfflineOnFirstLookupFailure));
    }

    @Test
    void reloadQuarantinesBrokenConfigAndRestoresDefaults() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("config.json"), "{broken");

        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        assertEquals(UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO, controller.config().bindingMode);
        assertTrue(Files.exists(tempDir.resolve("config.json")));
        assertTrue(Files.list(tempDir).anyMatch(path -> path.getFileName().toString().startsWith("config.broken-")));
    }

    @Test
    void reloadMigratesLegacySemiAutoConfigToUnsafeSemiAuto() throws Exception {
        Files.createDirectories(tempDir);
        UuidRestorerConfig legacy = new UuidRestorerConfig();
        legacy.bindingMode = UuidRestorerConfig.LEGACY_BINDING_MODE_SEMI_AUTO;
        Files.writeString(tempDir.resolve("config.json"), gson.toJson(legacy));

        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        assertEquals(UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO, controller.config().bindingMode);
        assertTrue(Files.readString(tempDir.resolve("config.json")).contains("\"bindingMode\": \"unsafe_semi_auto\""));
    }

    @Test
    void prepareLoginAutoBindsUnknownNicknameInUnsafeMode() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);
        ResolvedProfile profile = createProfile(UUID.randomUUID(), "Alice", "value", "sig");
        server.profilesByName.put("Alice", profile);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        ResolvedProfile appliedProfile = AuthlibCompat.toResolvedProfile(decision.replacementProfile());

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertEquals(profile.uuid(), appliedProfile.uuid());
        assertEquals("Alice", decision.replacementProfileName());
        assertEquals(1, server.resolveProfileByNameCalls);
        assertEquals(0, server.resolveProfileByIdCalls);
        assertEquals(0, server.fetchTexturesCalls);
        assertEquals(PlayerBinding.SOURCE_LEGACY_AUTO, controller.getBinding("Alice").orElseThrow().normalizedBindingSource());
    }

    @Test
    void prepareLoginPassesThroughWithoutBindingInManualCachedMode() throws Exception {
        Files.createDirectories(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        config.bindingMode = UuidRestorerConfig.BINDING_MODE_MANUAL_CACHED;
        Files.writeString(tempDir.resolve("config.json"), gson.toJson(config));

        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);
        server.profilesByName.put("Alice", createProfile(UUID.randomUUID(), "Alice", "value", "sig"));

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());

        assertEquals(LoginDecision.Action.PASS_THROUGH, decision.action());
        assertEquals(0, server.resolveProfileByNameCalls);
    }

    @Test
    void prepareLoginUsesCachedManualBindingWithoutNetworkCalls() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, true);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);

        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        ResolvedProfile appliedProfile = AuthlibCompat.toResolvedProfile(decision.replacementProfile());

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertEquals(binding.onlineUuid, appliedProfile.uuid());
        assertTrue(appliedProfile.hasTextures());
        assertEquals(0, server.resolveProfileByNameCalls);
        assertEquals(0, server.resolveProfileByIdCalls);
        assertEquals(0, server.fetchTexturesCalls);
    }

    @Test
    void prepareLoginUsesLegacyBindingAndAllowsMissingTexturesWithoutNetworkCalls() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_LEGACY_AUTO, false);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);

        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        ResolvedProfile appliedProfile = AuthlibCompat.toResolvedProfile(decision.replacementProfile());

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertFalse(appliedProfile.hasTextures());
        assertEquals(PlayerBinding.SOURCE_LEGACY_AUTO, controller.getBinding("Alice").orElseThrow().normalizedBindingSource());
        assertEquals(0, server.resolveProfileByNameCalls);
        assertEquals(0, server.resolveProfileByIdCalls);
        assertEquals(0, server.fetchTexturesCalls);
    }

    @Test
    void prepareLoginDeniesWhenCachedBindingHasDataConflict() throws Exception {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, true);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        Files.writeString(playerdataDir.resolve(binding.offlineUuid + ".dat"), "offline");
        Files.writeString(playerdataDir.resolve(binding.onlineUuid + ".dat"), "online");

        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());

        assertEquals(LoginDecision.Action.DENY, decision.action());
        assertTrue(decision.message().contains("offline and premium player data"));
    }

    @Test
    void resolveBindingConflictUpdatesStoredMigrationState() throws Exception {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, true);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        Files.writeString(playerdataDir.resolve(binding.offlineUuid + ".dat"), "offline");
        Files.writeString(playerdataDir.resolve(binding.onlineUuid + ".dat"), "premium");

        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);

        assertTrue(controller.resolveBindingConflict(server, "Alice", ResolutionScope.PLAYERDATA, ResolutionPreference.OFFLINE).orElseThrow().changed());

        PlayerBinding updated = controller.getBinding("Alice").orElseThrow();
        assertEquals("migrated", updated.migrationState);
        assertEquals("none", updated.conflictState);
        assertEquals("offline", Files.readString(playerdataDir.resolve(binding.onlineUuid + ".dat")));
    }

    @Test
    void bindNicknameCreatesTrustedBindingFromResolvedPremiumProfile() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        ResolvedProfile profile = createProfile(UUID.randomUUID(), "Alice", "value", "sig");
        TestServerAccess server = new TestServerAccess(tempDir);
        server.profilesByName.put("Alice", profile);

        PlayerBinding binding = controller.bindNickname(server, "Alice").orElseThrow();

        assertEquals(profile.uuid(), binding.onlineUuid);
        assertEquals("Alice", binding.canonicalName);
        assertEquals(PlayerBinding.SOURCE_MANUAL_CONFIRMED, binding.normalizedBindingSource());
        assertEquals("no_data", binding.migrationState);
        assertTrue(controller.getBinding("Alice").isPresent());
        assertEquals(1, server.resolveProfileByNameCalls);
    }

    @Test
    void bindNicknameReturnsEmptyWhenResolverThrowsError() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.throwOnResolveByName = new NoClassDefFoundError("legacy-authlib");

        assertTrue(controller.bindNickname(server, "Alice").isEmpty());
    }

    @Test
    void bindNicknameDetailedReportsLookupFailures() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.throwOnResolveByName = new IllegalStateException("network blocked");

        BindAttempt attempt = controller.bindNicknameDetailed(server, "Alice");

        assertEquals(BindAttemptStatus.LOOKUP_FAILED, attempt.status());
        assertTrue(attempt.binding().isEmpty());
        assertTrue(attempt.details().contains("network blocked"));
    }

    @Test
    void bindNicknameDetailedReportsRealNotFoundSeparately() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);

        BindAttempt attempt = controller.bindNicknameDetailed(server, "Alice");

        assertEquals(BindAttemptStatus.NOT_FOUND, attempt.status());
        assertTrue(attempt.binding().isEmpty());
    }

    @Test
    void refreshBindingUpdatesTexturesForExistingBinding() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, false);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.profilesById.put(binding.onlineUuid, createProfile(binding.onlineUuid, "Alice", "new-value", "new-sig"));

        PlayerBinding refreshed = controller.refreshBinding(server, "Alice").orElseThrow();

        assertTrue(refreshed.hasTextures());
        assertEquals("new-value", refreshed.texturesValue);
        assertEquals(PlayerBinding.SOURCE_MANUAL_CONFIRMED, refreshed.normalizedBindingSource());
        assertEquals(1, server.resolveProfileByIdCalls);
    }

    @Test
    void prepareLoginFallsBackWhenResolverThrowsError() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.throwOnResolveByName = new NoClassDefFoundError("legacy-authlib");

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());

        assertEquals(LoginDecision.Action.PASS_THROUGH, decision.action());
    }

    private static PlayerBinding createBinding(String name, String bindingSource, boolean withTextures) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = name.toLowerCase();
        binding.canonicalName = name;
        binding.onlineUuid = UUID.randomUUID();
        binding.offlineUuid = Uuids.getOfflinePlayerUuid(name);
        binding.texturesValue = withTextures ? "texture" : null;
        binding.texturesSignature = withTextures ? "signature" : null;
        binding.migrationState = "pending";
        binding.conflictState = "none";
        binding.bindingSource = bindingSource;
        return binding;
    }

    private static ResolvedProfile createProfile(UUID id, String name, String textureValue, String signature) {
        return new ResolvedProfile(id, name, textureValue, signature);
    }

    private static final class TestServerAccess implements ServerAccess {
        private final Path root;
        private final Map<String, ResolvedProfile> profilesByName = new HashMap<>();
        private final Map<UUID, ResolvedProfile> profilesById = new HashMap<>();
        private int resolveProfileByNameCalls;
        private int resolveProfileByIdCalls;
        private int fetchTexturesCalls;
        private boolean onlineMode;
        private Throwable throwOnResolveByName;
        private Throwable throwOnResolveById;
        private Throwable throwOnFetchTextures;

        private TestServerAccess(Path root) {
            this.root = root;
        }

        @Override
        public boolean isOnlineMode() {
            return onlineMode;
        }

        @Override
        public Optional<ResolvedProfile> resolveProfileByName(String name) {
            resolveProfileByNameCalls++;
            if (throwOnResolveByName != null) {
                throwUnchecked(throwOnResolveByName);
            }
            return Optional.ofNullable(profilesByName.get(name));
        }

        @Override
        public Optional<ResolvedProfile> resolveProfileById(UUID id) {
            resolveProfileByIdCalls++;
            if (throwOnResolveById != null) {
                throwUnchecked(throwOnResolveById);
            }
            return Optional.ofNullable(profilesById.get(id));
        }

        @Override
        public Optional<ResolvedProfile> fetchTextures(ResolvedProfile profile) {
            fetchTexturesCalls++;
            if (throwOnFetchTextures != null) {
                throwUnchecked(throwOnFetchTextures);
            }
            return Optional.ofNullable(profilesById.getOrDefault(profile.uuid(), profile));
        }

        @Override
        public Path rootPath() {
            return root;
        }

        @Override
        public Path playerdataDirectory() {
            return root.resolve("playerdata");
        }

        @Override
        public Path statsDirectory() {
            return root.resolve("stats");
        }

        @Override
        public Path advancementsDirectory() {
            return root.resolve("advancements");
        }

        private static void throwUnchecked(Throwable throwable) {
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(throwable);
        }
    }
}
