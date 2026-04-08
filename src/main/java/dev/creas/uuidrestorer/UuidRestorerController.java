package dev.creas.uuidrestorer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfile;
import dev.creas.uuidrestorer.config.UuidRestorerConfig;
import dev.creas.uuidrestorer.data.BindingStore;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.AuthlibCompat;
import dev.creas.uuidrestorer.runtime.MinecraftServerAccess;
import dev.creas.uuidrestorer.runtime.ResolvedProfile;
import dev.creas.uuidrestorer.runtime.ServerAccess;
import dev.creas.uuidrestorer.service.LoginDecision;
import dev.creas.uuidrestorer.service.MigrationReport;
import dev.creas.uuidrestorer.service.MigrationService;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionPreference;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionScope;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Uuids;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class UuidRestorerController {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final MigrationService migrationService = new MigrationService();
    private final Path baseDirectory;
    private final Path configFile;
    private final BindingStore bindingStore;

    private volatile UuidRestorerConfig config = UuidRestorerConfig.defaults();

    public UuidRestorerController(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
        this.configFile = baseDirectory.resolve("config.json");
        this.bindingStore = new BindingStore(baseDirectory.resolve("bindings.json"));
    }

    public synchronized void reload() {
        config = loadConfig();
        bindingStore.load();
    }

    public UuidRestorerConfig config() {
        return config;
    }

    public Optional<PlayerBinding> getBinding(String nickname) {
        return bindingStore.get(normalize(nickname));
    }

    public boolean removeBinding(String nickname) {
        return bindingStore.remove(normalize(nickname));
    }

    public LoginDecision prepareLogin(MinecraftServer server, String requestedName, UUID clientProfileId) {
        return prepareLogin(new MinecraftServerAccess(server), requestedName, clientProfileId);
    }

    LoginDecision prepareLogin(ServerAccess server, String requestedName, UUID clientProfileId) {
        UuidRestorerConfig currentConfig = config;
        if (server.isOnlineMode()) {
            return LoginDecision.passThrough();
        }

        Optional<PlayerBinding> existingBinding = bindingStore.get(normalize(requestedName));
        if (existingBinding.isEmpty()) {
            if (!isUnsafeSemiAutoBindingEnabled(currentConfig)) {
                return LoginDecision.passThrough();
            }

            String lookupKey = normalize(requestedName);
            UUID offlineUuid = Uuids.getOfflinePlayerUuid(requestedName);
            Optional<ResolvedProfile> resolvedProfile;
            try {
                resolvedProfile = resolveProfileByName(server, requestedName);
            } catch (Throwable throwable) {
                UuidRestorerMod.LOGGER.warn("Premium lookup failed for {}", requestedName, throwable);
                return isEnabled(currentConfig.allowOfflineOnFirstLookupFailure)
                    ? LoginDecision.passThrough()
                    : LoginDecision.deny("Premium lookup failed. Please try again later.");
            }

            if (resolvedProfile.isEmpty()) {
                return LoginDecision.passThrough();
            }

            PlayerBinding binding = bindingFromProfile(lookupKey, offlineUuid, clientProfileId, resolvedProfile.get(), PlayerBinding.SOURCE_LEGACY_AUTO);
            MigrationReport migration = migrationService.migrateIfSafe(server, currentConfig, binding);
            applyMigrationState(binding, migration);
            bindingStore.put(binding);

            if (migration.hasConflict() && isEnabled(currentConfig.denyOnConflict)) {
                UuidRestorerMod.LOGGER.warn("Created unsafe auto-binding for {} but blocked login because data already exists for both UUIDs", requestedName);
                return LoginDecision.deny("UUID restore blocked: both offline and premium player data exist. Use /uuidrestorer status and /uuidrestorer migrate after resolving the conflict.");
            }

            UuidRestorerMod.LOGGER.warn("Auto-bound {} to premium UUID {} using unsafe nickname-based mode", requestedName, binding.onlineUuid);
            return LoginDecision.apply(toGameProfile(binding), binding.canonicalName);
        }

        PlayerBinding binding = existingBinding.get();
        MigrationReport migration = migrationService.migrateIfSafe(server, currentConfig, binding);
        boolean changed = applyMigrationState(binding, migration);
        if (changed) {
            bindingStore.put(binding);
        }

        if (!binding.isTrusted()) {
            UuidRestorerMod.LOGGER.warn("Applying legacy/insecure binding for {}", requestedName);
        }
        if (migration.hasConflict() && isEnabled(currentConfig.denyOnConflict)) {
            UuidRestorerMod.LOGGER.warn("Blocked premium login for {} because online and offline data conflict", requestedName);
            return LoginDecision.deny("UUID restore blocked: both offline and premium player data exist. Ask an admin to resolve the conflict.");
        }

        return LoginDecision.apply(toGameProfile(binding), binding.canonicalName);
    }

    public Optional<PlayerBinding> bindNickname(MinecraftServer server, String nickname) {
        return bindNicknameDetailed(server, nickname).binding();
    }

    Optional<PlayerBinding> bindNickname(ServerAccess server, String nickname) {
        return bindNicknameDetailed(server, nickname).binding();
    }

    public BindAttempt bindNicknameDetailed(MinecraftServer server, String nickname) {
        return bindNicknameDetailed(new MinecraftServerAccess(server), nickname);
    }

    BindAttempt bindNicknameDetailed(ServerAccess server, String nickname) {
        UuidRestorerConfig currentConfig = config;
        UUID offlineUuid = Uuids.getOfflinePlayerUuid(nickname);
        try {
            Optional<PlayerBinding> binding = resolveProfileByName(server, nickname).map(profile -> {
                PlayerBinding createdBinding = bindingFromProfile(normalize(nickname), offlineUuid, null, profile, PlayerBinding.SOURCE_MANUAL_CONFIRMED);
                MigrationReport migration = migrationService.inspect(server, currentConfig, createdBinding);
                createdBinding.migrationState = migration.migrationState();
                createdBinding.conflictState = migration.conflictState();
                bindingStore.put(createdBinding);
                return createdBinding;
            });
            return binding
                .map(BindAttempt::success)
                .orElseGet(BindAttempt::notFound);
        } catch (Throwable throwable) {
            UuidRestorerMod.LOGGER.warn("Manual bind failed for {}", nickname, throwable);
            return BindAttempt.lookupFailed(summarizeThrowable(throwable));
        }
    }

    public Optional<PlayerBinding> refreshBinding(MinecraftServer server, String nickname) {
        return refreshBinding(new MinecraftServerAccess(server), nickname);
    }

    Optional<PlayerBinding> refreshBinding(ServerAccess server, String nickname) {
        UuidRestorerConfig currentConfig = config;
        Optional<PlayerBinding> existingBinding = bindingStore.get(normalize(nickname));
        if (existingBinding.isEmpty()) {
            return Optional.empty();
        }

        PlayerBinding existing = existingBinding.get();
        try {
            return resolveProfileForRefresh(server, nickname, existing).map(profile -> {
                PlayerBinding refreshed = bindingFromProfile(existing.lookupKey, existing.offlineUuid, existing.lastClientProfileId, profile, existing.normalizedBindingSource());
                MigrationReport migration = migrationService.inspect(server, currentConfig, refreshed);
                refreshed.migrationState = migration.migrationState();
                refreshed.conflictState = migration.conflictState();
                bindingStore.put(refreshed);
                return refreshed;
            });
        } catch (Throwable throwable) {
            UuidRestorerMod.LOGGER.warn("Profile refresh failed for {}", nickname, throwable);
            return Optional.empty();
        }
    }

    public Optional<MigrationReport> migrateBinding(MinecraftServer server, String nickname) {
        return migrateBinding(new MinecraftServerAccess(server), nickname);
    }

    Optional<MigrationReport> migrateBinding(ServerAccess server, String nickname) {
        UuidRestorerConfig currentConfig = config;
        return bindingStore.get(normalize(nickname)).map(binding -> {
            MigrationReport report = migrationService.migrateIfSafe(server, currentConfig, binding);
            if (applyMigrationState(binding, report)) {
                bindingStore.put(binding);
            }
            return report;
        });
    }

    public Optional<MigrationReport> inspectBinding(MinecraftServer server, String nickname) {
        return inspectBinding(new MinecraftServerAccess(server), nickname);
    }

    Optional<MigrationReport> inspectBinding(ServerAccess server, String nickname) {
        UuidRestorerConfig currentConfig = config;
        return bindingStore.get(normalize(nickname)).map(binding -> migrationService.inspect(server, currentConfig, binding));
    }

    public Optional<MigrationReport> resolveBindingConflict(MinecraftServer server, String nickname, ResolutionScope scope, ResolutionPreference preference) {
        return resolveBindingConflict(new MinecraftServerAccess(server), nickname, scope, preference);
    }

    Optional<MigrationReport> resolveBindingConflict(ServerAccess server, String nickname, ResolutionScope scope, ResolutionPreference preference) {
        UuidRestorerConfig currentConfig = config;
        return bindingStore.get(normalize(nickname)).map(binding -> {
            MigrationReport report = migrationService.resolveSelection(server, currentConfig, binding, scope, preference);
            if (applyMigrationState(binding, report)) {
                bindingStore.put(binding);
            }
            return report;
        });
    }

    private UuidRestorerConfig loadConfig() {
        try {
            Files.createDirectories(baseDirectory);
            if (!Files.exists(configFile)) {
                UuidRestorerConfig defaults = UuidRestorerConfig.defaults();
                saveConfig(defaults);
                return defaults;
            }

            UuidRestorerConfig loaded;
            try (Reader reader = Files.newBufferedReader(configFile)) {
                loaded = gson.fromJson(reader, UuidRestorerConfig.class);
            }

            SanitizedConfig sanitized = sanitizeConfig(loaded);
            if (sanitized.changed()) {
                saveConfig(sanitized.config());
            }
            return sanitized.config();
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            UuidRestorerMod.LOGGER.error("Failed to load config from {}", configFile, exception);
            quarantineBrokenConfig();
            UuidRestorerConfig defaults = UuidRestorerConfig.defaults();
            try {
                saveConfig(defaults);
            } catch (IOException saveException) {
                UuidRestorerMod.LOGGER.error("Failed to rewrite default config to {}", configFile, saveException);
            }
            return defaults;
        }
    }

    private void saveConfig(UuidRestorerConfig config) throws IOException {
        Path tempFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempFile)) {
            gson.toJson(config, writer);
        }
        Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private SanitizedConfig sanitizeConfig(UuidRestorerConfig loaded) {
        UuidRestorerConfig defaults = UuidRestorerConfig.defaults();
        if (loaded == null) {
            return new SanitizedConfig(defaults, true);
        }

        boolean changed = false;
        UuidRestorerConfig sanitized = new UuidRestorerConfig();

        String bindingMode = loaded.bindingMode;
        if (bindingMode == null || bindingMode.isBlank()) {
            bindingMode = UuidRestorerConfig.BINDING_MODE_MANUAL_CACHED;
            changed = true;
        } else {
            bindingMode = bindingMode.toLowerCase(Locale.ROOT);
            if (UuidRestorerConfig.LEGACY_BINDING_MODE_SEMI_AUTO.equals(bindingMode)) {
                UuidRestorerMod.LOGGER.warn("Deprecated bindingMode 'semi_auto' detected. Migrating config to '{}'.", UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO);
                bindingMode = UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO;
                changed = true;
            } else if (!UuidRestorerConfig.BINDING_MODE_MANUAL_CACHED.equals(bindingMode) && !UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO.equals(bindingMode)) {
                UuidRestorerMod.LOGGER.warn("Unknown bindingMode '{}' detected. Falling back to '{}'.", loaded.bindingMode, UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO);
                bindingMode = UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO;
                changed = true;
            }
        }
        sanitized.bindingMode = bindingMode;

        sanitized.allowOfflineOnFirstLookupFailure = chooseBoolean(loaded.allowOfflineOnFirstLookupFailure, defaults.allowOfflineOnFirstLookupFailure);
        sanitized.migratePlayerdata = chooseBoolean(loaded.migratePlayerdata, defaults.migratePlayerdata);
        sanitized.migrateStats = chooseBoolean(loaded.migrateStats, defaults.migrateStats);
        sanitized.migrateAdvancements = chooseBoolean(loaded.migrateAdvancements, defaults.migrateAdvancements);
        sanitized.denyOnConflict = chooseBoolean(loaded.denyOnConflict, defaults.denyOnConflict);
        sanitized.touchServerLists = chooseBoolean(loaded.touchServerLists, defaults.touchServerLists);

        changed |= loaded.allowOfflineOnFirstLookupFailure == null
            || loaded.migratePlayerdata == null
            || loaded.migrateStats == null
            || loaded.migrateAdvancements == null
            || loaded.denyOnConflict == null
            || loaded.touchServerLists == null;

        if (UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO.equals(bindingMode)) {
            UuidRestorerMod.LOGGER.warn("bindingMode '{}' is insecure: unknown nicknames may be auto-bound to Mojang profiles during login.", bindingMode);
        }

        return new SanitizedConfig(sanitized, changed);
    }

    private Optional<ResolvedProfile> resolveProfileByName(ServerAccess server, String nickname) {
        return server.resolveProfileByName(nickname).flatMap(profile -> enrichProfile(server, profile));
    }

    private Optional<ResolvedProfile> resolveProfileForRefresh(ServerAccess server, String nickname, PlayerBinding binding) {
        return server.resolveProfileById(binding.onlineUuid)
            .flatMap(profile -> enrichProfile(server, profile))
            .or(() -> resolveProfileByName(server, binding.canonicalName))
            .or(() -> resolveProfileByName(server, nickname));
    }

    private Optional<ResolvedProfile> enrichProfile(ServerAccess server, ResolvedProfile profile) {
        ResolvedProfile current = profile;
        if (!current.hasTextures()) {
            current = server.fetchTextures(current).orElse(current);
        }
        return Optional.ofNullable(current.uuid() == null ? null : current);
    }

    private PlayerBinding bindingFromProfile(String lookupKey, UUID offlineUuid, UUID clientProfileId, ResolvedProfile profile, String bindingSource) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = lookupKey;
        binding.canonicalName = profile.name();
        binding.onlineUuid = profile.uuid();
        binding.offlineUuid = offlineUuid;
        binding.texturesValue = profile.texturesValue();
        binding.texturesSignature = profile.texturesSignature();
        binding.lastResolvedAt = Instant.now().toString();
        binding.lastClientProfileId = clientProfileId;
        binding.migrationState = "pending";
        binding.conflictState = "none";
        binding.bindingSource = bindingSource;
        return binding;
    }

    private boolean applyMigrationState(PlayerBinding binding, MigrationReport report) {
        String nextMigrationState = report.migrationState();
        String nextConflictState = report.conflictState();
        boolean changed = !Objects.equals(binding.migrationState, nextMigrationState) || !Objects.equals(binding.conflictState, nextConflictState);
        binding.migrationState = nextMigrationState;
        binding.conflictState = nextConflictState;
        return changed;
    }

    private void quarantineBrokenConfig() {
        if (!Files.exists(configFile)) {
            return;
        }

        Path brokenFile = configFile.resolveSibling(brokenFileName(configFile));
        try {
            Files.move(configFile, brokenFile, StandardCopyOption.REPLACE_EXISTING);
            UuidRestorerMod.LOGGER.warn("Moved broken config file to {}", brokenFile);
        } catch (IOException moveException) {
            UuidRestorerMod.LOGGER.error("Failed to move broken config file {}", configFile, moveException);
        }
    }

    private GameProfile toGameProfile(PlayerBinding binding) {
        return AuthlibCompat.createGameProfile(binding.onlineUuid, binding.canonicalName, binding.texturesValue, binding.texturesSignature);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static Boolean chooseBoolean(Boolean value, Boolean fallback) {
        return value != null ? value : fallback;
    }

    private static boolean isEnabled(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static boolean isUnsafeSemiAutoBindingEnabled(UuidRestorerConfig config) {
        return UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO.equalsIgnoreCase(config.bindingMode);
    }

    private static String summarizeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static String brokenFileName(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        return name + ".broken-" + Instant.now().toString().replace(":", "-") + ".json";
    }

    public record BindAttempt(BindAttemptStatus status, Optional<PlayerBinding> binding, String details) {
        public static BindAttempt success(PlayerBinding binding) {
            return new BindAttempt(BindAttemptStatus.SUCCESS, Optional.of(binding), null);
        }

        public static BindAttempt notFound() {
            return new BindAttempt(BindAttemptStatus.NOT_FOUND, Optional.empty(), null);
        }

        public static BindAttempt lookupFailed(String details) {
            return new BindAttempt(BindAttemptStatus.LOOKUP_FAILED, Optional.empty(), details);
        }
    }

    public enum BindAttemptStatus {
        SUCCESS,
        NOT_FOUND,
        LOOKUP_FAILED
    }

    private record SanitizedConfig(UuidRestorerConfig config, boolean changed) {
    }
}
