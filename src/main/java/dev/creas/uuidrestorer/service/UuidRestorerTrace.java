package dev.creas.uuidrestorer.service;

import com.mojang.authlib.GameProfile;
import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.AuthlibCompat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class UuidRestorerTrace {
    private static final Object LOCK = new Object();

    private static Path traceFile;

    private UuidRestorerTrace() {
    }

    public static void initialize(Path baseDirectory) {
        synchronized (LOCK) {
            try {
                Files.createDirectories(baseDirectory);
                traceFile = baseDirectory.resolve("trace.log");
                Files.writeString(
                    traceFile,
                    "# UUID Restorer trace\n# started=" + Instant.now() + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
                log("trace", "initialized file=" + traceFile.toAbsolutePath());
            } catch (IOException exception) {
                UuidRestorerMod.LOGGER.error("Failed to initialize UUID Restorer trace log", exception);
            }
        }
    }

    public static Path path() {
        synchronized (LOCK) {
            return traceFile;
        }
    }

    public static void log(String scope, String message) {
        write(scope, message, null);
    }

    public static void log(String scope, String message, Throwable throwable) {
        write(scope, message, throwable);
    }

    public static void binding(String scope, String label, PlayerBinding binding) {
        log(scope, label + "=" + describeBinding(binding));
    }

    public static void preparation(String scope, String label, LoginPreparation preparation) {
        log(scope, label + "=" + describePreparation(preparation));
    }

    public static void migration(String scope, String label, MigrationReport report) {
        log(scope, label + "=" + describeMigration(report));
    }

    public static void decision(String scope, String label, LoginDecision decision) {
        log(scope, label + "=" + describeDecision(decision));
    }

    public static String describeBinding(PlayerBinding binding) {
        if (binding == null) {
            return "null";
        }
        return "PlayerBinding{"
            + "lookupKey=" + binding.lookupKey
            + ", canonicalName=" + binding.canonicalName
            + ", offlineUuid=" + binding.offlineUuid
            + ", onlineUuid=" + binding.onlineUuid
            + ", lookupState=" + binding.normalizedLookupState()
            + ", trusted=" + binding.isTrusted()
            + ", textures=" + binding.hasTextures()
            + ", migrationState=" + binding.migrationState
            + ", conflictState=" + binding.conflictState
            + ", lastClientProfileId=" + binding.lastClientProfileId
            + "}";
    }

    public static String describePreparation(LoginPreparation preparation) {
        if (preparation == null) {
            return "null";
        }
        return "LoginPreparation{"
            + "requestedName=" + preparation.requestedName()
            + ", clientProfileId=" + preparation.clientProfileId()
            + ", offlineUuid=" + preparation.offlineUuid()
            + ", lookupState=" + preparation.lookupState()
            + ", resolvedProfile=" + describeProfile(preparation.resolvedProfile())
            + ", lookupDetails=" + preparation.lookupDetails()
            + "}";
    }

    public static String describeMigration(MigrationReport report) {
        if (report == null) {
            return "null";
        }
        return "MigrationReport{"
            + "migrationState=" + report.migrationState()
            + ", conflictState=" + report.conflictState()
            + ", changed=" + report.changed()
            + ", onlineTargetAvailable=" + report.onlineTargetAvailable()
            + ", playerdata=" + describeFileState(report.playerdata())
            + ", playerdataOld=" + describeFileState(report.playerdataOld())
            + ", stats=" + describeFileState(report.stats())
            + ", advancements=" + describeFileState(report.advancements())
            + "}";
    }

    public static String describeProfile(dev.creas.uuidrestorer.runtime.ResolvedProfile profile) {
        if (profile == null) {
            return "null";
        }
        return "ResolvedProfile{"
            + "uuid=" + profile.uuid()
            + ", name=" + profile.name()
            + ", textures=" + profile.hasTextures()
            + ", texturesValueLength=" + (profile.texturesValue() == null ? 0 : profile.texturesValue().length())
            + ", texturesSignatureLength=" + (profile.texturesSignature() == null ? 0 : profile.texturesSignature().length())
            + "}";
    }

    public static String describeGameProfile(GameProfile profile) {
        if (profile == null) {
            return "null";
        }
        return "GameProfile{"
            + "id=" + AuthlibCompat.readUuid(profile)
            + ", name=" + AuthlibCompat.readName(profile)
            + ", textures=" + AuthlibCompat.readProperties(profile, "textures").size()
            + "}";
    }

    public static String describeDecision(LoginDecision decision) {
        if (decision == null) {
            return "null";
        }
        return "LoginDecision{"
            + "action=" + decision.action()
            + ", replacementProfile=" + describeGameProfile(decision.replacementProfile())
            + ", replacementProfileName=" + decision.replacementProfileName()
            + ", message=" + decision.message()
            + "}";
    }

    public static String describeFileState(MigrationReport.FileState state) {
        if (state == null) {
            return "null";
        }
        return "{enabled=" + state.enabled()
            + ", sourceExists=" + state.sourceExists()
            + ", targetExists=" + state.targetExists()
            + ", conflict=" + state.conflict()
            + ", canMove=" + state.canMove()
            + "}";
    }

    private static void write(String scope, String message, Throwable throwable) {
        synchronized (LOCK) {
            if (traceFile == null) {
                return;
            }

            StringBuilder line = new StringBuilder()
                .append(Instant.now())
                .append(" [")
                .append(Thread.currentThread().getName())
                .append("] ")
                .append(scope)
                .append(" :: ")
                .append(message)
                .append(System.lineSeparator());

            if (throwable != null) {
                StringWriter buffer = new StringWriter();
                throwable.printStackTrace(new PrintWriter(buffer));
                line.append(buffer);
                if (!buffer.toString().endsWith(System.lineSeparator())) {
                    line.append(System.lineSeparator());
                }
            }

            try {
                Files.writeString(
                    traceFile,
                    line.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
                );
            } catch (IOException exception) {
                UuidRestorerMod.LOGGER.error("Failed to write UUID Restorer trace log", exception);
            }
        }
    }
}
