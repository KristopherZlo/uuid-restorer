package dev.creas.uuidrestorer.service;

import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.config.UuidRestorerConfig;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.MinecraftServerAccess;
import dev.creas.uuidrestorer.runtime.ServerAccess;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Uuids;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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
        boolean onlineTargetAvailable = binding.hasOnlineProfile();

        Path playerdataSource = playerdataPath(server, binding.offlineUuid);
        Path playerdataTarget = onlineTargetAvailable ? playerdataPath(server, binding.onlineUuid) : null;
        Path playerdataOldSource = playerdataOldPath(server, binding.offlineUuid);
        Path playerdataOldTarget = onlineTargetAvailable ? playerdataOldPath(server, binding.onlineUuid) : null;
        Path statsSource = statsPath(server, binding.offlineUuid);
        Path statsTarget = onlineTargetAvailable ? statsPath(server, binding.onlineUuid) : null;
        Path advancementsSource = advancementsPath(server, binding.offlineUuid);
        Path advancementsTarget = onlineTargetAvailable ? advancementsPath(server, binding.onlineUuid) : null;

        MigrationReport report = new MigrationReport(
            fileState(config.migratePlayerdata, playerdataSource, playerdataTarget),
            fileState(config.migratePlayerdata, playerdataOldSource, playerdataOldTarget),
            fileState(config.migrateStats, statsSource, statsTarget),
            fileState(config.migrateAdvancements, advancementsSource, advancementsTarget),
            false,
            onlineTargetAvailable
        );
        UuidRestorerTrace.log(
            "migration",
            "inspect binding=" + binding.canonicalName
                + " offlineUuid=" + binding.offlineUuid
                + " onlineUuid=" + binding.onlineUuid
                + " playerdataSource=" + playerdataSource
                + " playerdataTarget=" + playerdataTarget
                + " playerdataOldSource=" + playerdataOldSource
                + " playerdataOldTarget=" + playerdataOldTarget
                + " statsSource=" + statsSource
                + " statsTarget=" + statsTarget
                + " advancementsSource=" + advancementsSource
                + " advancementsTarget=" + advancementsTarget
                + " report=" + UuidRestorerTrace.describeMigration(report)
        );
        return report;
    }

    public MigrationReport migrateIfSafe(MinecraftServer server, UuidRestorerConfig config, PlayerBinding binding) {
        return migrateIfSafe(new MinecraftServerAccess(server), config, binding);
    }

    public MigrationReport migrateIfSafe(ServerAccess server, UuidRestorerConfig config, PlayerBinding binding) {
        MigrationReport initial = inspect(server, config, binding);
        UuidRestorerTrace.log(
            "migration",
            "migrateIfSafe start binding=" + binding.canonicalName
                + " offlineUuid=" + binding.offlineUuid
                + " onlineUuid=" + binding.onlineUuid
                + " initial=" + UuidRestorerTrace.describeMigration(initial)
        );
        if (!initial.onlineTargetAvailable() || initial.hasConflict()) {
            UuidRestorerTrace.log(
                "migration",
                "migrateIfSafe skipped reason="
                    + (!initial.onlineTargetAvailable() ? "noOnlineTarget" : "conflict")
            );
            return initial;
        }

        boolean changed = false;
        String timestamp = Instant.now().toString().replace(":", "-");
        try {
            if (initial.playerdata().canMove()) {
                UuidRestorerTrace.log("migration", "migrateIfSafe rewriteAndMove bucket=playerdata");
                rewriteAndMovePlayerData(
                    playerdataPath(server, binding.offlineUuid),
                    playerdataPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "playerdata"),
                    binding.offlineUuid,
                    binding.onlineUuid
                );
                changed = true;
            }
            if (initial.playerdataOld().canMove()) {
                UuidRestorerTrace.log("migration", "migrateIfSafe rewriteAndMove bucket=playerdata_old");
                rewriteAndMovePlayerData(
                    playerdataOldPath(server, binding.offlineUuid),
                    playerdataOldPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "playerdata"),
                    binding.offlineUuid,
                    binding.onlineUuid
                );
                changed = true;
            }
            if (initial.stats().canMove()) {
                UuidRestorerTrace.log("migration", "migrateIfSafe move bucket=stats");
                backupAndMove(
                    statsPath(server, binding.offlineUuid),
                    statsPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "stats")
                );
                changed = true;
            }
            if (initial.advancements().canMove()) {
                UuidRestorerTrace.log("migration", "migrateIfSafe move bucket=advancements");
                backupAndMove(
                    advancementsPath(server, binding.offlineUuid),
                    advancementsPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "advancements")
                );
                changed = true;
            }
        } catch (IOException exception) {
            UuidRestorerTrace.log("migration", "migrateIfSafe failed binding=" + binding.canonicalName, exception);
            UuidRestorerMod.LOGGER.error(
                "Failed to migrate files for {} [{} -> {}]",
                binding.canonicalName,
                binding.offlineUuid,
                binding.onlineUuid,
                exception
            );
        }

        MigrationReport after = inspect(server, config, binding);
        MigrationReport report = new MigrationReport(
            after.playerdata(),
            after.playerdataOld(),
            after.stats(),
            after.advancements(),
            changed,
            after.onlineTargetAvailable()
        );
        UuidRestorerTrace.log("migration", "migrateIfSafe result=" + UuidRestorerTrace.describeMigration(report));
        return report;
    }

    public MigrationReport resolveSelection(MinecraftServer server, UuidRestorerConfig config, PlayerBinding binding, ResolutionScope scope, ResolutionPreference preference) {
        return resolveSelection(new MinecraftServerAccess(server), config, binding, scope, preference);
    }

    public MigrationReport resolveSelection(ServerAccess server, UuidRestorerConfig config, PlayerBinding binding, ResolutionScope scope, ResolutionPreference preference) {
        MigrationReport initial = inspect(server, config, binding);
        UuidRestorerTrace.log(
            "migration",
            "resolveSelection start binding=" + binding.canonicalName
                + " scope=" + scope.serializedName()
                + " preference=" + preference.serializedName()
                + " initial=" + UuidRestorerTrace.describeMigration(initial)
        );
        if (!initial.onlineTargetAvailable()) {
            UuidRestorerTrace.log("migration", "resolveSelection skipped reason=noOnlineTarget");
            return initial;
        }

        boolean changed = false;
        String timestamp = Instant.now().toString().replace(":", "-");
        try {
            if (Boolean.TRUE.equals(config.migratePlayerdata) && (scope == ResolutionScope.PLAYERDATA || scope == ResolutionScope.ALL)) {
                changed |= resolvePlayerData(
                    playerdataPath(server, binding.offlineUuid),
                    playerdataPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "resolve-playerdata"),
                    preference,
                    binding.offlineUuid,
                    binding.onlineUuid
                );
                changed |= resolvePlayerData(
                    playerdataOldPath(server, binding.offlineUuid),
                    playerdataOldPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "resolve-playerdata"),
                    preference,
                    binding.offlineUuid,
                    binding.onlineUuid
                );
            }
            if (Boolean.TRUE.equals(config.migrateStats) && (scope == ResolutionScope.STATS || scope == ResolutionScope.ALL)) {
                changed |= resolveBucket(
                    statsPath(server, binding.offlineUuid),
                    statsPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "resolve-stats"),
                    preference
                );
            }
            if (Boolean.TRUE.equals(config.migrateAdvancements) && (scope == ResolutionScope.ADVANCEMENTS || scope == ResolutionScope.ALL)) {
                changed |= resolveBucket(
                    advancementsPath(server, binding.offlineUuid),
                    advancementsPath(server, binding.onlineUuid),
                    backupDirectory(server, binding, timestamp, "resolve-advancements"),
                    preference
                );
            }
        } catch (IOException exception) {
            UuidRestorerTrace.log(
                "migration",
                "resolveSelection failed binding=" + binding.canonicalName
                    + " scope=" + scope.serializedName()
                    + " preference=" + preference.serializedName(),
                exception
            );
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
        MigrationReport report = new MigrationReport(
            after.playerdata(),
            after.playerdataOld(),
            after.stats(),
            after.advancements(),
            changed,
            after.onlineTargetAvailable()
        );
        UuidRestorerTrace.log("migration", "resolveSelection result=" + UuidRestorerTrace.describeMigration(report));
        return report;
    }

    private static MigrationReport.FileState fileState(Boolean enabledFlag, Path source, Path target) {
        boolean enabled = Boolean.TRUE.equals(enabledFlag);
        boolean sourceExists = source != null && Files.exists(source);
        boolean targetExists = target != null && Files.exists(target);
        return new MigrationReport.FileState(enabled, sourceExists, targetExists);
    }

    private static void backupAndMove(Path source, Path target, Path backupDir) throws IOException {
        UuidRestorerTrace.log("migration", "backupAndMove source=" + source + " target=" + target + " backupDir=" + backupDir);
        Files.createDirectories(target.getParent());
        Files.createDirectories(backupDir);

        Path backupTarget = backupDir.resolve(source.getFileName().toString());
        Files.copy(source, backupTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void rewriteAndMovePlayerData(Path source, Path target, Path backupDir, UUID offlineUuid, UUID onlineUuid) throws IOException {
        UuidRestorerTrace.log(
            "migration",
            "rewriteAndMovePlayerData source=" + source
                + " target=" + target
                + " backupDir=" + backupDir
                + " offlineUuid=" + offlineUuid
                + " onlineUuid=" + onlineUuid
        );
        Files.createDirectories(target.getParent());
        Files.createDirectories(backupDir);

        backupIfExists(source, backupDir);

        NbtCompound playerData = NbtIo.readCompressed(source, NbtSizeTracker.ofUnlimitedBytes());
        rewriteUuidRecursive(playerData, offlineUuid, onlineUuid);
        writeCompressedAtomically(playerData, target);
        Files.delete(source);
    }

    private static boolean resolvePlayerData(Path source, Path target, Path backupDir, ResolutionPreference preference, UUID offlineUuid, UUID onlineUuid) throws IOException {
        return switch (preference) {
            case OFFLINE -> preferOfflinePlayerData(source, target, backupDir, offlineUuid, onlineUuid);
            case PREMIUM -> preferPremium(source, target, backupDir);
        };
    }

    private static boolean resolveBucket(Path source, Path target, Path backupDir, ResolutionPreference preference) throws IOException {
        return switch (preference) {
            case OFFLINE -> preferOffline(source, target, backupDir);
            case PREMIUM -> preferPremium(source, target, backupDir);
        };
    }

    private static boolean preferOffline(Path source, Path target, Path backupDir) throws IOException {
        if (!Files.exists(source)) {
            UuidRestorerTrace.log("migration", "preferOffline skipped missing source=" + source);
            return false;
        }

        UuidRestorerTrace.log("migration", "preferOffline source=" + source + " target=" + target + " backupDir=" + backupDir);
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

    private static boolean preferOfflinePlayerData(Path source, Path target, Path backupDir, UUID offlineUuid, UUID onlineUuid) throws IOException {
        if (!Files.exists(source)) {
            UuidRestorerTrace.log("migration", "preferOfflinePlayerData skipped missing source=" + source);
            return false;
        }

        UuidRestorerTrace.log(
            "migration",
            "preferOfflinePlayerData source=" + source
                + " target=" + target
                + " backupDir=" + backupDir
                + " offlineUuid=" + offlineUuid
                + " onlineUuid=" + onlineUuid
        );
        Files.createDirectories(target.getParent());
        Files.createDirectories(backupDir);
        backupIfExists(source, backupDir);
        if (Files.exists(target)) {
            backupIfExists(target, backupDir);
        }

        NbtCompound playerData = NbtIo.readCompressed(source, NbtSizeTracker.ofUnlimitedBytes());
        rewriteUuidRecursive(playerData, offlineUuid, onlineUuid);
        writeCompressedAtomically(playerData, target);
        Files.delete(source);
        return true;
    }

    private static boolean preferPremium(Path source, Path target, Path backupDir) throws IOException {
        if (!Files.exists(source) || !Files.exists(target)) {
            UuidRestorerTrace.log(
                "migration",
                "preferPremium skipped sourceExists=" + Files.exists(source)
                    + " targetExists=" + Files.exists(target)
                    + " source=" + source
                    + " target=" + target
            );
            return false;
        }

        UuidRestorerTrace.log("migration", "preferPremium source=" + source + " target=" + target + " backupDir=" + backupDir);
        Files.createDirectories(backupDir);
        backupIfExists(source, backupDir);
        Files.delete(source);
        return true;
    }

    private static void backupIfExists(Path file, Path backupDir) throws IOException {
        if (!Files.exists(file)) {
            UuidRestorerTrace.log("migration", "backupIfExists skipped missing file=" + file);
            return;
        }

        UuidRestorerTrace.log("migration", "backupIfExists file=" + file + " backupDir=" + backupDir);
        Path backupTarget = backupDir.resolve(file.getFileName().toString());
        Files.copy(file, backupTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void writeCompressedAtomically(NbtCompound compound, Path target) throws IOException {
        UuidRestorerTrace.log("migration", "writeCompressedAtomically target=" + target);
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(compound, tempFile);
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void rewriteUuidRecursive(NbtElement element, UUID offlineUuid, UUID onlineUuid) {
        if (element instanceof NbtCompound compound) {
            for (String key : List.copyOf(compound.getKeys())) {
                NbtElement child = compound.get(key);
                if (child == null) {
                    continue;
                }

                NbtElement rewritten = rewriteDirectUuid(child, offlineUuid, onlineUuid);
                if (rewritten != child) {
                    compound.put(key, rewritten);
                    child = rewritten;
                }
                rewriteUuidRecursive(child, offlineUuid, onlineUuid);
            }
            return;
        }

        if (element instanceof NbtList list) {
            for (int index = 0; index < list.size(); index++) {
                NbtElement child = list.get(index);
                NbtElement rewritten = rewriteDirectUuid(child, offlineUuid, onlineUuid);
                if (rewritten != child) {
                    list.set(index, rewritten);
                    child = rewritten;
                }
                rewriteUuidRecursive(child, offlineUuid, onlineUuid);
            }
        }
    }

    private static NbtElement rewriteDirectUuid(NbtElement element, UUID offlineUuid, UUID onlineUuid) {
        int[] offlineIntArray = Uuids.toIntArray(offlineUuid);
        int[] onlineIntArray = Uuids.toIntArray(onlineUuid);
        long[] offlineLongArray = toLongArray(offlineUuid);
        long[] onlineLongArray = toLongArray(onlineUuid);

        if (element instanceof NbtIntArray intArray && Arrays.equals(intArray.getIntArray(), offlineIntArray)) {
            return new NbtIntArray(onlineIntArray);
        }
        if (element instanceof NbtLongArray longArray && Arrays.equals(longArray.getLongArray(), offlineLongArray)) {
            return new NbtLongArray(onlineLongArray);
        }
        if (element instanceof NbtString string && offlineUuid.toString().equals(string.asString())) {
            return NbtString.of(onlineUuid.toString());
        }
        return element;
    }

    private static long[] toLongArray(UUID uuid) {
        return new long[] {uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()};
    }

    private static Path backupDirectory(ServerAccess server, PlayerBinding binding, String timestamp, String bucket) {
        return server.rootPath()
            .resolve("uuid-restorer-backups")
            .resolve(binding.canonicalName)
            .resolve(timestamp)
            .resolve(bucket);
    }

    private static Path playerdataPath(ServerAccess server, UUID uuid) {
        return server.playerdataDirectory().resolve(uuid + ".dat");
    }

    private static Path playerdataOldPath(ServerAccess server, UUID uuid) {
        return server.playerdataDirectory().resolve(uuid + ".dat_old");
    }

    private static Path statsPath(ServerAccess server, UUID uuid) {
        return server.statsDirectory().resolve(uuid + ".json");
    }

    private static Path advancementsPath(ServerAccess server, UUID uuid) {
        return server.advancementsDirectory().resolve(uuid + ".json");
    }
}
