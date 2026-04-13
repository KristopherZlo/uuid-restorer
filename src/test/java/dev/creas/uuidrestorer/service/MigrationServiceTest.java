package dev.creas.uuidrestorer.service;

import dev.creas.uuidrestorer.config.UuidRestorerConfig;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.ResolvedProfile;
import dev.creas.uuidrestorer.runtime.ServerAccess;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void migrateIfSafeMovesFilesRewritesUuidAndCreatesBackup() throws Exception {
        ServerAccess server = serverAccess(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        PlayerBinding binding = foundBinding("Alice");
        MigrationService service = new MigrationService();

        Path playerdataSource = tempDir.resolve("playerdata").resolve(binding.offlineUuid + ".dat");
        Path playerdataOldSource = tempDir.resolve("playerdata").resolve(binding.offlineUuid + ".dat_old");
        Path statsSource = tempDir.resolve("stats").resolve(binding.offlineUuid + ".json");
        Path advancementsSource = tempDir.resolve("advancements").resolve(binding.offlineUuid + ".json");
        Files.createDirectories(playerdataSource.getParent());
        Files.createDirectories(statsSource.getParent());
        Files.createDirectories(advancementsSource.getParent());
        writePlayerData(playerdataSource, binding.offlineUuid, "playerdata");
        writePlayerData(playerdataOldSource, binding.offlineUuid, "playerdata-old");
        Files.writeString(statsSource, "stats");
        Files.writeString(advancementsSource, "advancements");

        MigrationReport report = service.migrateIfSafe(server, config, binding);

        Path migratedPlayerdata = tempDir.resolve("playerdata").resolve(binding.onlineUuid + ".dat");
        Path migratedPlayerdataOld = tempDir.resolve("playerdata").resolve(binding.onlineUuid + ".dat_old");
        NbtCompound playerdata = NbtIo.readCompressed(migratedPlayerdata, NbtSizeTracker.ofUnlimitedBytes());
        NbtCompound playerdataOld = NbtIo.readCompressed(migratedPlayerdataOld, NbtSizeTracker.ofUnlimitedBytes());

        assertTrue(report.changed());
        assertEquals("migrated", report.migrationState());
        assertTrue(Files.exists(migratedPlayerdata));
        assertTrue(Files.exists(migratedPlayerdataOld));
        assertTrue(Files.exists(tempDir.resolve("stats").resolve(binding.onlineUuid + ".json")));
        assertTrue(Files.exists(tempDir.resolve("advancements").resolve(binding.onlineUuid + ".json")));
        assertFalse(Files.exists(playerdataSource));
        assertFalse(Files.exists(playerdataOldSource));
        assertArrayEquals(Uuids.toIntArray(binding.onlineUuid), playerdata.getIntArray("UUID").orElseThrow());
        assertArrayEquals(Uuids.toIntArray(binding.onlineUuid), playerdataOld.getIntArray("UUID").orElseThrow());
        assertEquals("playerdata", playerdata.getString("marker").orElseThrow());
        assertEquals("playerdata-old", playerdataOld.getString("marker").orElseThrow());
        assertTrue(Files.exists(tempDir.resolve("uuid-restorer-backups")));
    }

    @Test
    void inspectAndMigrateIfSafeKeepConflictsUntouched() throws Exception {
        ServerAccess server = serverAccess(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        PlayerBinding binding = foundBinding("Conflict");
        MigrationService service = new MigrationService();

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        writePlayerData(playerdataDir.resolve(binding.offlineUuid + ".dat"), binding.offlineUuid, "offline");
        writePlayerData(playerdataDir.resolve(binding.onlineUuid + ".dat"), binding.onlineUuid, "online");

        MigrationReport inspect = service.inspect(server, config, binding);
        MigrationReport migrate = service.migrateIfSafe(server, config, binding);

        assertTrue(inspect.hasConflict());
        assertTrue(migrate.hasConflict());
        assertFalse(migrate.changed());
        assertEquals("conflict", migrate.migrationState());
        assertEquals("playerdata", migrate.conflictState());
    }

    @Test
    void resolveSelectionOfflinePromotesOfflineConflictDataToPremiumUuid() throws Exception {
        ServerAccess server = serverAccess(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        PlayerBinding binding = foundBinding("OfflineWins");
        MigrationService service = new MigrationService();

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        Path source = playerdataDir.resolve(binding.offlineUuid + ".dat");
        Path target = playerdataDir.resolve(binding.onlineUuid + ".dat");
        writePlayerData(source, binding.offlineUuid, "offline");
        writePlayerData(target, binding.onlineUuid, "premium");

        MigrationReport report = service.resolveSelection(server, config, binding, ResolutionScope.PLAYERDATA, ResolutionPreference.OFFLINE);
        NbtCompound migrated = NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes());

        assertTrue(report.changed());
        assertEquals("migrated", report.migrationState());
        assertFalse(report.hasConflict());
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(target));
        assertEquals("offline", migrated.getString("marker").orElseThrow());
        assertArrayEquals(Uuids.toIntArray(binding.onlineUuid), migrated.getIntArray("UUID").orElseThrow());
        assertTrue(Files.exists(tempDir.resolve("uuid-restorer-backups")));
    }

    @Test
    void resolveSelectionPremiumKeepsPremiumConflictDataAndRemovesOfflineSource() throws Exception {
        ServerAccess server = serverAccess(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        PlayerBinding binding = foundBinding("PremiumWins");
        MigrationService service = new MigrationService();

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        Path source = playerdataDir.resolve(binding.offlineUuid + ".dat");
        Path target = playerdataDir.resolve(binding.onlineUuid + ".dat");
        writePlayerData(source, binding.offlineUuid, "offline");
        writePlayerData(target, binding.onlineUuid, "premium");

        MigrationReport report = service.resolveSelection(server, config, binding, ResolutionScope.PLAYERDATA, ResolutionPreference.PREMIUM);
        NbtCompound migrated = NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes());

        assertTrue(report.changed());
        assertEquals("migrated", report.migrationState());
        assertFalse(report.hasConflict());
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(target));
        assertEquals("premium", migrated.getString("marker").orElseThrow());
        assertArrayEquals(Uuids.toIntArray(binding.onlineUuid), migrated.getIntArray("UUID").orElseThrow());
        assertTrue(Files.exists(tempDir.resolve("uuid-restorer-backups")));
    }

    @Test
    void inspectWithoutPremiumUuidReportsOfflineOnlyState() throws Exception {
        ServerAccess server = serverAccess(tempDir);
        UuidRestorerConfig config = UuidRestorerConfig.defaults();
        PlayerBinding binding = offlineOnlyBinding("OfflineOnly");
        MigrationService service = new MigrationService();

        Path playerdataDir = tempDir.resolve("playerdata");
        Files.createDirectories(playerdataDir);
        writePlayerData(playerdataDir.resolve(binding.offlineUuid + ".dat"), binding.offlineUuid, "offline");

        MigrationReport report = service.inspect(server, config, binding);

        assertFalse(report.onlineTargetAvailable());
        assertEquals("offline_only", report.migrationState());
        assertFalse(report.hasConflict());
        assertTrue(report.playerdata().sourceExists());
        assertFalse(report.playerdata().targetExists());
    }

    private static ServerAccess serverAccess(Path root) {
        return new TestServerAccess(root);
    }

    private static PlayerBinding foundBinding(String name) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = name.toLowerCase();
        binding.canonicalName = name;
        binding.onlineUuid = UUID.randomUUID();
        binding.offlineUuid = UUID.randomUUID();
        binding.lookupState = PlayerBinding.LookupState.FOUND;
        binding.texturesValue = "value";
        binding.texturesSignature = "sig";
        return binding;
    }

    private static PlayerBinding offlineOnlyBinding(String name) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = name.toLowerCase();
        binding.canonicalName = name;
        binding.onlineUuid = null;
        binding.offlineUuid = UUID.randomUUID();
        binding.lookupState = PlayerBinding.LookupState.NOT_FOUND;
        return binding;
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

        private TestServerAccess(Path root) {
            this.root = root;
        }

        @Override
        public boolean isOnlineMode() {
            return false;
        }

        @Override
        public boolean isDedicatedServer() {
            return true;
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
