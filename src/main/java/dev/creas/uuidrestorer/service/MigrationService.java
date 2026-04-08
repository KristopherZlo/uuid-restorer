package dev.creas.uuidrestorer.service;

import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.config.UuidRestorerConfig;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.MinecraftServerAccess;
import dev.creas.uuidrestorer.runtime.ServerAccess;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class MigrationService {
    public enum ResolutionScope {
        PLAYERDATA("playerdata"),
        STATS("stats"),
        ADVANCEMENTS("advancements"),
        ALL("all");

        private final String serializedName;

        ResolutionScope(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }
    }

    public enum ResolutionPreference {
        OFFLINE("offline"),
        PREMIUM("premium");

        private final String serializedName;

        ResolutionPreference(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }
    }

    public MigrationReport inspect(MinecraftServer server, UuidRestorerConfig config, PlayerBinding binding) {
        return inspect(new MinecraftServerAccess(server), config, binding);
    }

    public MigrationReport inspect(ServerAccess server, UuidRestorerConfig config, PlayerBinding binding) {
        Path playerdataSource = playerdataPath(server, binding.offlineUuid.toString());
        Path playerdataTarget = playerdataPath(server, binding.onlineUuid.toString());
        Path statsSource = statsPath(server, binding.offlineUuid.toString());
        Path statsTarget = statsPath(server, binding.onlineUuid.toString());
        Path advancementsSource = advancementsPath(server, binding.offlineUuid.toString());
        Path advancementsTarget = advancementsPath(server, binding.onlineUuid.toString());

        return new MigrationReport(
            new MigrationReport.FileState(config.migratePlayerdata, Files.exists(playerdataSource), Files.exists(playerdataTarget)),
            new MigrationReport.FileState(config.migrateStats, Files.exists(statsSource), Files.exists(statsTarget)),
            new MigrationReport.FileState(config.migrateAdvancements, Files.exists(advancementsSource), Files.exists(advancementsTarget)),
            false
        );
    }

    public MigrationReport migrateIfSafe(MinecraftServer server, UuidRestorerConfig config, PlayerBinding binding) {
        return migrateIfSafe(new MinecraftServerAccess(server), config, binding);
    }

    public MigrationReport migrateIfSafe(ServerAccess server, UuidRestorerConfig config, PlayerBinding binding) {
        MigrationReport initial = inspect(server, config, binding);
        if (initial.hasConflict()) {
            return initial;
        }

        boolean changed = false;
        String timestamp = Instant.now().toString().replace(":", "-");
        try {
            if (initial.playerdata().canMove()) {
                backupAndMove(playerdataPath(server, binding.offlineUuid.toString()), playerdataPath(server, binding.onlineUuid.toString()), backupDirectory(server, binding, timestamp, "playerdata"));
                changed = true;
            }
            if (initial.stats().canMove()) {
                backupAndMove(statsPath(server, binding.offlineUuid.toString()), statsPath(server, binding.onlineUuid.toString()), backupDirectory(server, binding, timestamp, "stats"));
                changed = true;
            }
            if (initial.advancements().canMove()) {
                backupAndMove(advancementsPath(server, binding.offlineUuid.toString()), advancementsPath(server, binding.onlineUuid.toString()), backupDirectory(server, binding, timestamp, "advancements"));
                changed = true;
            }
        } catch (IOException exception) {
            UuidRestorerMod.LOGGER.error("Failed to migrate files for {} [{} -> {}]", binding.canonicalName, binding.offlineUuid, binding.onlineUuid, exception);
        }

        MigrationReport after = inspect(server, config, binding);
        return new MigrationReport(after.playerdata(), after.stats(), after.advancements(), changed);
    }

    public MigrationReport resolveSelection(MinecraftServer server, UuidRestorerConfig config, PlayerBinding binding, ResolutionScope scope, ResolutionPreference preference) {
        return resolveSelection(new MinecraftServerAccess(server), config, binding, scope, preference);
    }

    public MigrationReport resolveSelection(ServerAccess server, UuidRestorerConfig config, PlayerBinding binding, ResolutionScope scope, ResolutionPreference preference) {
        boolean changed = false;
        String timestamp = Instant.now().toString().replace(":", "-");
        try {
            if (Boolean.TRUE.equals(config.migratePlayerdata) && (scope == ResolutionScope.PLAYERDATA || scope == ResolutionScope.ALL)) {
                changed |= resolveBucket(
                    playerdataPath(server, binding.offlineUuid.toString()),
                    playerdataPath(server, binding.onlineUuid.toString()),
                    backupDirectory(server, binding, timestamp, "resolve-playerdata"),
                    preference
                );
            }
            if (Boolean.TRUE.equals(config.migrateStats) && (scope == ResolutionScope.STATS || scope == ResolutionScope.ALL)) {
                changed |= resolveBucket(
                    statsPath(server, binding.offlineUuid.toString()),
                    statsPath(server, binding.onlineUuid.toString()),
                    backupDirectory(server, binding, timestamp, "resolve-stats"),
                    preference
                );
            }
            if (Boolean.TRUE.equals(config.migrateAdvancements) && (scope == ResolutionScope.ADVANCEMENTS || scope == ResolutionScope.ALL)) {
                changed |= resolveBucket(
                    advancementsPath(server, binding.offlineUuid.toString()),
                    advancementsPath(server, binding.onlineUuid.toString()),
                    backupDirectory(server, binding, timestamp, "resolve-advancements"),
                    preference
                );
            }
        } catch (IOException exception) {
            UuidRestorerMod.LOGGER.error(
                "Failed to resolve migration conflict for {} [{} -> {}] using {}:{}",
                binding.canonicalName,
                binding.offlineUuid,
                binding.onlineUuid,
                scope.serializedName(),
                preference.serializedName(),
                exception
            );
        }

        MigrationReport after = inspect(server, config, binding);
        return new MigrationReport(after.playerdata(), after.stats(), after.advancements(), changed);
    }

    private static void backupAndMove(Path source, Path target, Path backupDir) throws IOException {
        Files.createDirectories(target.getParent());
        Files.createDirectories(backupDir);

        Path backupTarget = backupDir.resolve(source.getFileName().toString());
        Files.copy(source, backupTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(source, target);
    }

    private static boolean resolveBucket(Path source, Path target, Path backupDir, ResolutionPreference preference) throws IOException {
        return switch (preference) {
            case OFFLINE -> preferOffline(source, target, backupDir);
            case PREMIUM -> preferPremium(source, target, backupDir);
        };
    }

    private static boolean preferOffline(Path source, Path target, Path backupDir) throws IOException {
        if (!Files.exists(source)) {
            return false;
        }

        Files.createDirectories(target.getParent());
        Files.createDirectories(backupDir);
        backupIfExists(source, backupDir);
        if (Files.exists(target)) {
            backupIfExists(target, backupDir);
            Files.delete(target);
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static boolean preferPremium(Path source, Path target, Path backupDir) throws IOException {
        if (!Files.exists(source) || !Files.exists(target)) {
            return false;
        }

        Files.createDirectories(backupDir);
        backupIfExists(source, backupDir);
        Files.delete(source);
        return true;
    }

    private static void backupIfExists(Path file, Path backupDir) throws IOException {
        if (!Files.exists(file)) {
            return;
        }

        Path backupTarget = backupDir.resolve(file.getFileName().toString());
        Files.copy(file, backupTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static Path backupDirectory(ServerAccess server, PlayerBinding binding, String timestamp, String bucket) {
        return server.rootPath()
            .resolve("uuid-restorer-backups")
            .resolve(binding.canonicalName)
            .resolve(timestamp)
            .resolve(bucket);
    }

    private static Path playerdataPath(ServerAccess server, String fileName) {
        return server.playerdataDirectory().resolve(fileName + ".dat");
    }

    private static Path statsPath(ServerAccess server, String fileName) {
        return server.statsDirectory().resolve(fileName + ".json");
    }

    private static Path advancementsPath(ServerAccess server, String fileName) {
        return server.advancementsDirectory().resolve(fileName + ".json");
    }
}
