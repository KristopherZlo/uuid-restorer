package dev.creas.uuidrestorer;

import com.google.gson.Gson;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttempt;
import dev.creas.uuidrestorer.UuidRestorerController.BindAttemptStatus;
import dev.creas.uuidrestorer.config.UuidRestorerConfig;
import dev.creas.uuidrestorer.data.BindingStore;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.AuthlibCompat;
import dev.creas.uuidrestorer.runtime.ResolvedProfile;
import dev.creas.uuidrestorer.runtime.ServerAccess;
import dev.creas.uuidrestorer.service.LoginDecision;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionPreference;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionScope;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.Uuids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void prepareLoginAutoRestoresPremiumProfileAndStoresFoundBinding() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);
        ResolvedProfile profile = createProfile(UUID.randomUUID(), "Alice", "value", "sig");
        server.profilesByName.put("Alice", profile);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        ResolvedProfile appliedProfile = AuthlibCompat.toResolvedProfile(decision.replacementProfile());
        PlayerBinding binding = controller.getBinding("Alice").orElseThrow();

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertEquals(profile.uuid(), appliedProfile.uuid());
        assertEquals("Alice", decision.replacementProfileName());
        assertEquals(PlayerBinding.LookupState.FOUND, binding.normalizedLookupState());
        assertEquals(profile.uuid(), binding.onlineUuid);
        assertEquals(Uuids.getOfflinePlayerUuid("Alice"), binding.offlineUuid);
        assertTrue(binding.hasTextures());
        assertEquals(1, server.resolveProfileByNameCalls);
    }

    @Test
    void prepareLoginStillAutoRestoresWhenManualCachedModeIsConfigured() throws Exception {
        Files.createDirectories(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        config.bindingMode = UuidRestorerConfig.BINDING_MODE_MANUAL_CACHED;
        Files.writeString(tempDir.resolve("config.json"), gson.toJson(config));

        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);
        server.profilesByName.put("Alice", createProfile(UUID.randomUUID(), "Alice", "value", "sig"));

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertEquals(PlayerBinding.LookupState.FOUND, controller.getBinding("Alice").orElseThrow().normalizedLookupState());
        assertEquals(1, server.resolveProfileByNameCalls);
    }

    @Test
    void prepareLoginStillRestoresOnNonDedicatedServerEvenWhenOnlineModeIsTrue() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.onlineMode = true;
        server.dedicatedServer = false;
        ResolvedProfile profile = createProfile(UUID.randomUUID(), "Alice", "value", "sig");
        server.profilesByName.put("Alice", profile);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        ResolvedProfile appliedProfile = AuthlibCompat.toResolvedProfile(decision.replacementProfile());

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertEquals(profile.uuid(), appliedProfile.uuid());
        assertEquals(1, server.resolveProfileByNameCalls);
    }

    @Test
    void prepareLoginPassesThroughOnDedicatedOnlineModeServer() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.onlineMode = true;
        server.dedicatedServer = true;
        server.profilesByName.put("Alice", createProfile(UUID.randomUUID(), "Alice", "value", "sig"));

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());

        assertEquals(LoginDecision.Action.PASS_THROUGH, decision.action());
        assertEquals(0, server.resolveProfileByNameCalls);
        assertTrue(controller.getBinding("Alice").isEmpty());
    }

    @Test
    void prepareLoginFallsBackToOfflineWhenPremiumNicknameIsMissingAndStoresNotFoundBinding() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        PlayerBinding binding = controller.getBinding("Alice").orElseThrow();

        assertEquals(LoginDecision.Action.PASS_THROUGH, decision.action());
        assertEquals(PlayerBinding.LookupState.NOT_FOUND, binding.normalizedLookupState());
        assertNull(binding.onlineUuid);
        assertEquals("Alice", binding.canonicalName);
        assertEquals("no_data", binding.migrationState);
        assertEquals(1, server.resolveProfileByNameCalls);
    }

    @Test
    void prepareLoginFallsBackToOfflineWhenResolverThrowsAndStoresLookupFailure() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.throwOnResolveByName = new IllegalStateException("network blocked");

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        PlayerBinding binding = controller.getBinding("Alice").orElseThrow();

        assertEquals(LoginDecision.Action.PASS_THROUGH, decision.action());
        assertEquals(PlayerBinding.LookupState.LOOKUP_FAILED, binding.normalizedLookupState());
        assertNull(binding.onlineUuid);
        assertEquals("Alice", binding.canonicalName);
    }

    @Test
    void prepareLoginUsesStoredTrustedBindingWhenLookupFails() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, PlayerBinding.LookupState.FOUND, true);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.throwOnResolveByName = new IllegalStateException("network blocked");

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        PlayerBinding stored = controller.getBinding("Alice").orElseThrow();
        ResolvedProfile appliedProfile = AuthlibCompat.toResolvedProfile(decision.replacementProfile());

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertEquals(binding.onlineUuid, appliedProfile.uuid());
        assertEquals(PlayerBinding.SOURCE_MANUAL_CONFIRMED, stored.normalizedBindingSource());
        assertEquals(PlayerBinding.LookupState.FOUND, stored.normalizedLookupState());
        assertEquals(binding.onlineUuid, stored.onlineUuid);
    }

    @Test
    void prepareLoginPreservesManualBindingSourceOnSuccessfulLookup() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, PlayerBinding.LookupState.FOUND, false);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        ResolvedProfile profile = createProfile(binding.onlineUuid, "Alice", "fresh-texture", "fresh-signature");
        server.profilesByName.put("Alice", profile);
        server.profilesById.put(binding.onlineUuid, profile);

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());
        PlayerBinding stored = controller.getBinding("Alice").orElseThrow();

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertEquals(PlayerBinding.SOURCE_MANUAL_CONFIRMED, stored.normalizedBindingSource());
        assertTrue(stored.hasTextures());
        assertEquals("fresh-texture", stored.texturesValue);
    }

    @Test
    void prepareLoginDeniesWhenResolvedPremiumHasDataConflict() throws Exception {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        ResolvedProfile profile = createProfile(UUID.randomUUID(), "Alice", "value", "sig");
        server.profilesByName.put("Alice", profile);

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        writePlayerData(playerdataDir.resolve(Uuids.getOfflinePlayerUuid("Alice") + ".dat"), Uuids.getOfflinePlayerUuid("Alice"), "offline");
        writePlayerData(playerdataDir.resolve(profile.uuid() + ".dat"), profile.uuid(), "online");

        LoginDecision decision = controller.prepareLogin(server, "Alice", UUID.randomUUID());

        assertEquals(LoginDecision.Action.DENY, decision.action());
        assertTrue(decision.message().contains("offline and premium player data"));
    }

    @Test
    void prepareLoginAutoPrefersPremiumForTrustedBindingWhenClientAlreadyUsesPremiumUuid() throws Exception {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, PlayerBinding.LookupState.FOUND, true);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        ResolvedProfile profile = createProfile(binding.onlineUuid, "Alice", "value", "sig");
        server.profilesByName.put("Alice", profile);
        server.profilesById.put(binding.onlineUuid, profile);

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        writePlayerData(playerdataDir.resolve(binding.offlineUuid + ".dat"), binding.offlineUuid, "offline");
        writePlayerData(playerdataDir.resolve(binding.onlineUuid + ".dat"), binding.onlineUuid, "online");

        LoginDecision decision = controller.prepareLogin(server, "Alice", binding.onlineUuid);

        assertEquals(LoginDecision.Action.APPLY_PREMIUM, decision.action());
        assertFalse(Files.exists(playerdataDir.resolve(binding.offlineUuid + ".dat")));
        assertTrue(Files.exists(playerdataDir.resolve(binding.onlineUuid + ".dat")));
        PlayerBinding stored = controller.getBinding("Alice").orElseThrow();
        assertEquals("migrated", stored.migrationState);
        assertEquals("none", stored.conflictState);
    }

    @Test
    void resolveBindingConflictUpdatesStoredMigrationState() throws Exception {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, PlayerBinding.LookupState.FOUND, true);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        writePlayerData(playerdataDir.resolve(binding.offlineUuid + ".dat"), binding.offlineUuid, "offline");
        writePlayerData(playerdataDir.resolve(binding.onlineUuid + ".dat"), binding.onlineUuid, "premium");

        controller.reload();
        TestServerAccess server = new TestServerAccess(tempDir);

        assertTrue(controller.resolveBindingConflict(server, "Alice", ResolutionScope.PLAYERDATA, ResolutionPreference.OFFLINE).orElseThrow().changed());

        PlayerBinding updated = controller.getBinding("Alice").orElseThrow();
        NbtCompound migrated = NbtIo.readCompressed(playerdataDir.resolve(binding.onlineUuid + ".dat"), NbtSizeTracker.ofUnlimitedBytes());
        assertEquals("migrated", updated.migrationState);
        assertEquals("none", updated.conflictState);
        assertEquals("offline", migrated.getString("marker").orElseThrow());
        assertArrayEquals(Uuids.toIntArray(binding.onlineUuid), migrated.getIntArray("UUID").orElseThrow());
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
        assertEquals(PlayerBinding.LookupState.FOUND, binding.normalizedLookupState());
        assertEquals("no_data", binding.migrationState);
        assertTrue(controller.getBinding("Alice").isPresent());
        assertEquals(1, server.resolveProfileByNameCalls);
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
    void refreshBindingUpdatesTexturesForExistingPremiumBinding() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, PlayerBinding.LookupState.FOUND, false);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.profilesById.put(binding.onlineUuid, createProfile(binding.onlineUuid, "Alice", "new-value", "new-sig"));

        PlayerBinding refreshed = controller.refreshBinding(server, "Alice").orElseThrow();

        assertTrue(refreshed.hasTextures());
        assertEquals("new-value", refreshed.texturesValue);
        assertEquals(PlayerBinding.LookupState.FOUND, refreshed.normalizedLookupState());
        assertEquals(1, server.resolveProfileByIdCalls);
    }

    @Test
    void refreshBindingCanPromoteOfflineOnlyBindingWhenPremiumProfileAppears() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_LEGACY_AUTO, PlayerBinding.LookupState.NOT_FOUND, false);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        ResolvedProfile profile = createProfile(UUID.randomUUID(), "Alice", "value", "sig");
        server.profilesByName.put("Alice", profile);

        PlayerBinding refreshed = controller.refreshBinding(server, "Alice").orElseThrow();

        assertEquals(PlayerBinding.LookupState.FOUND, refreshed.normalizedLookupState());
        assertEquals(profile.uuid(), refreshed.onlineUuid);
        assertTrue(refreshed.hasTextures());
    }

    @Test
    void refreshBindingDoesNotDropExistingPremiumBindingWhenLookupFails() {
        UuidRestorerController controller = new UuidRestorerController(tempDir);
        controller.reload();

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED, PlayerBinding.LookupState.FOUND, true);
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();
        store.put(binding);
        controller.reload();

        TestServerAccess server = new TestServerAccess(tempDir);
        server.throwOnResolveById = new IllegalStateException("session server down");

        PlayerBinding refreshed = controller.refreshBinding(server, "Alice").orElseThrow();

        assertEquals(PlayerBinding.LookupState.FOUND, refreshed.normalizedLookupState());
        assertEquals(binding.onlineUuid, refreshed.onlineUuid);
        assertEquals(PlayerBinding.SOURCE_MANUAL_CONFIRMED, refreshed.normalizedBindingSource());
        assertTrue(refreshed.hasTextures());
    }

    private static PlayerBinding createBinding(String name, String bindingSource, PlayerBinding.LookupState lookupState, boolean withTextures) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = name.toLowerCase();
        binding.canonicalName = name;
        binding.offlineUuid = Uuids.getOfflinePlayerUuid(name);
        binding.lookupState = lookupState;
        binding.onlineUuid = lookupState == PlayerBinding.LookupState.FOUND ? UUID.randomUUID() : null;
        binding.texturesValue = withTextures && binding.onlineUuid != null ? "texture" : null;
        binding.texturesSignature = withTextures && binding.onlineUuid != null ? "signature" : null;
        binding.migrationState = "pending";
        binding.conflictState = "none";
        binding.bindingSource = bindingSource;
        return binding;
    }

    private static ResolvedProfile createProfile(UUID id, String name, String textureValue, String signature) {
        return new ResolvedProfile(id, name, textureValue, signature);
    }

    private static void writePlayerData(Path file, UUID uuid, String marker) throws Exception {
        NbtCompound compound = new NbtCompound();
        compound.putIntArray("UUID", Uuids.toIntArray(uuid));
        compound.putString("marker", marker);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(compound, file);
    }

    private static final class TestServerAccess implements ServerAccess {
        private final Path root;
        private final Map<String, ResolvedProfile> profilesByName = new HashMap<>();
        private final Map<UUID, ResolvedProfile> profilesById = new HashMap<>();
        private int resolveProfileByNameCalls;
        private int resolveProfileByIdCalls;
        private int fetchTexturesCalls;
        private boolean onlineMode;
        private boolean dedicatedServer;
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
        public boolean isDedicatedServer() {
            return dedicatedServer;
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
