package dev.creas.uuidrestorer.service;

import dev.creas.uuidrestorer.config.UuidRestorerConfig;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.ResolvedProfile;
import dev.creas.uuidrestorer.runtime.ServerAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void migrateIfSafeMovesFilesAndCreatesBackup() throws Exception {
        ServerAccess server = serverAccess(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        PlayerBinding binding = binding("Alice");
        MigrationService service = new MigrationService();

        Path playerdataSource = tempDir.resolve("playerdata").resolve(binding.offlineUuid + ".dat");
        Path statsSource = tempDir.resolve("stats").resolve(binding.offlineUuid + ".json");
        Path advancementsSource = tempDir.resolve("advancements").resolve(binding.offlineUuid + ".json");
        Files.createDirectories(playerdataSource.getParent());
        Files.createDirectories(statsSource.getParent());
        Files.createDirectories(advancementsSource.getParent());
        Files.writeString(playerdataSource, "playerdata");
        Files.writeString(statsSource, "stats");
        Files.writeString(advancementsSource, "advancements");

        MigrationReport report = service.migrateIfSafe(server, config, binding);

        assertTrue(report.changed());
        assertEquals("migrated", report.migrationState());
        assertTrue(Files.exists(tempDir.resolve("playerdata").resolve(binding.onlineUuid + ".dat")));
        assertTrue(Files.exists(tempDir.resolve("stats").resolve(binding.onlineUuid + ".json")));
        assertTrue(Files.exists(tempDir.resolve("advancements").resolve(binding.onlineUuid + ".json")));
        assertFalse(Files.exists(playerdataSource));
        assertTrue(Files.exists(tempDir.resolve("uuid-restorer-backups")));
    }

    @Test
    void inspectAndMigrateIfSafeKeepConflictsUntouched() throws Exception {
        ServerAccess server = serverAccess(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        PlayerBinding binding = binding("Conflict");
        MigrationService service = new MigrationService();

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        Files.writeString(playerdataDir.resolve(binding.offlineUuid + ".dat"), "offline");
        Files.writeString(playerdataDir.resolve(binding.onlineUuid + ".dat"), "online");

        MigrationReport inspect = service.inspect(server, config, binding);
        MigrationReport migrate = service.migrateIfSafe(server, config, binding);

        assertTrue(inspect.hasConflict());
        assertTrue(migrate.hasConflict());
        assertFalse(migrate.changed());
        assertEquals("conflict", migrate.migrationState());
        assertEquals("playerdata", migrate.conflictState());
    }

    private static ServerAccess serverAccess(Path root) {
        return new TestServerAccess(root);
    }

    private static PlayerBinding binding(String name) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = name.toLowerCase();
        binding.canonicalName = name;
        binding.onlineUuid = UUID.randomUUID();
        binding.offlineUuid = UUID.randomUUID();
        binding.texturesValue = "value";
        binding.texturesSignature = "sig";
        return binding;
    }

    private static final class TestServerAccess implements ServerAccess {
        private final Path root;

        private TestServerAccess(Path root) {
            this.root = root;
        }

        @Override
        public boolean isOnlineMode() {
            return false;
        }

        @Override
        public java.util.Optional<ResolvedProfile> resolveProfileByName(String name) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ResolvedProfile> resolveProfileById(UUID id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ResolvedProfile> fetchTextures(ResolvedProfile profile) {
            return java.util.Optional.of(profile);
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
    }
}
