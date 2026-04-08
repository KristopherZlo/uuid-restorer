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

    private static void backupAndMove(Path source, Path target, Path backupDir) throws IOException {
        Files.createDirectories(target.getParent());
        Files.createDirectories(backupDir);

        Path backupTarget = backupDir.resolve(source.getFileName().toString());
        Files.copy(source, backupTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(source, target);
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
