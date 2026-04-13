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
import dev.creas.uuidrestorer.service.LoginPreparation;
import dev.creas.uuidrestorer.service.MigrationReport;
import dev.creas.uuidrestorer.service.MigrationService;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionPreference;
import dev.creas.uuidrestorer.service.MigrationService.ResolutionScope;
import dev.creas.uuidrestorer.service.UuidRestorerTrace;
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
import java.util.concurrent.CompletableFuture;

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
        UuidRestorerTrace.log("controller", "reload bindingMode=" + config.bindingMode
            + " migratePlayerdata=" + config.migratePlayerdata
            + " migrateStats=" + config.migrateStats
            + " migrateAdvancements=" + config.migrateAdvancements
            + " denyOnConflict=" + config.denyOnConflict);
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
        if (shouldPassThroughForServer(server)) {
            UuidRestorerTrace.log(
                "controller",
                "prepareLogin requestedName=" + requestedName
                    + " onlineMode=" + server.isOnlineMode()
                    + " dedicated=" + server.isDedicatedServer()
                    + " action=passThrough"
            );
            return LoginDecision.passThrough();
        }
        UuidRestorerTrace.log("controller", "prepareLogin requestedName=" + requestedName + " clientProfileId=" + clientProfileId);
        return completeLogin(server, lookupLogin(server, requestedName, clientProfileId));
    }

    public CompletableFuture<LoginPreparation> prepareLoginAsync(MinecraftServer server, String requestedName, UUID clientProfileId) {
        return prepareLoginAsync(new MinecraftServerAccess(server), requestedName, clientProfileId);
    }

    CompletableFuture<LoginPreparation> prepareLoginAsync(ServerAccess server, String requestedName, UUID clientProfileId) {
        if (shouldPassThroughForServer(server)) {
            UuidRestorerTrace.log(
                "controller",
                "prepareLoginAsync requestedName=" + requestedName
                    + " onlineMode=" + server.isOnlineMode()
                    + " dedicated=" + server.isDedicatedServer()
                    + " action=completedOffline"
            );
            return CompletableFuture.completedFuture(new LoginPreparation(
                requestedName,
                clientProfileId,
                Uuids.getOfflinePlayerUuid(requestedName),
                PlayerBinding.LookupState.NOT_FOUND,
                null,
                null
            ));
        }
        UuidRestorerTrace.log("controller", "prepareLoginAsync requestedName=" + requestedName + " clientProfileId=" + clientProfileId + " action=supplyAsync");
        return CompletableFuture.supplyAsync(() -> lookupLogin(server, requestedName, clientProfileId));
    }

    public LoginDecision completeLogin(MinecraftServer server, LoginPreparation preparation) {
        return completeLogin(new MinecraftServerAccess(server), preparation);
    }

    LoginDecision completeLogin(ServerAccess server, LoginPreparation preparation) {
        if (shouldPassThroughForServer(server)) {
            UuidRestorerTrace.preparation("controller", "completeLogin.preparation", preparation);
            UuidRestorerTrace.log(
                "controller",
                "completeLogin action=passThrough onlineMode=" + server.isOnlineMode() + " dedicated=" + server.isDedicatedServer()
            );
            return LoginDecision.passThrough();
        }

        UuidRestorerTrace.preparation("controller", "completeLogin.preparation", preparation);
        UuidRestorerConfig currentConfig = config;
        String lookupKey = normalize(preparation.requestedName());
        Optional<PlayerBinding> existingBinding = bindingStore.get(lookupKey);
        existingBinding.ifPresent(binding -> UuidRestorerTrace.binding("controller", "completeLogin.existingBinding", binding));
        PlayerBinding binding = bindingForLogin(lookupKey, preparation, existingBinding);
        UuidRestorerTrace.binding("controller", "completeLogin.selectedBinding", binding);

        MigrationReport migration = migrationService.inspect(server, currentConfig, binding);
        if (binding.hasOnlineProfile()) {
            migration = migrationService.migrateIfSafe(server, currentConfig, binding);
        }

        applyMigrationState(binding, migration);
        bindingStore.put(binding);
        UuidRestorerTrace.migration("controller", "completeLogin.migration", migration);
        UuidRestorerTrace.binding("controller", "completeLogin.persistedBinding", binding);

        if (!binding.hasOnlineProfile()) {
            if (binding.normalizedLookupState() == PlayerBinding.LookupState.LOOKUP_FAILED) {
                UuidRestorerMod.LOGGER.warn(
                    "Premium lookup failed for {}, continuing with offline UUID ({})",
                    preparation.requestedName(),
                    preparation.lookupDetails()
                );
            }
            UuidRestorerTrace.log("controller", "completeLogin action=passThrough reason=noOnlineProfile");
            return LoginDecision.passThrough();
        }

        if (!preparation.hasOnlineProfile() && existingBinding.isPresent()) {
            UuidRestorerMod.LOGGER.warn(
                "Using stored premium binding for {} after live lookup returned {}",
                preparation.requestedName(),
                preparation.lookupState()
            );
            UuidRestorerTrace.log("controller", "completeLogin usingStoredBinding requestedName=" + preparation.requestedName() + " lookupState=" + preparation.lookupState());
        }
        if (!binding.isTrusted()) {
            UuidRestorerMod.LOGGER.warn("Applying auto-restored premium binding for {}", preparation.requestedName());
            UuidRestorerTrace.log("controller", "completeLogin bindingTrusted=false requestedName=" + preparation.requestedName());
        }
        if (migration.hasConflict() && isEnabled(currentConfig.denyOnConflict)) {
            if (shouldAutoPreferPremiumOnLogin(preparation, binding)) {
                UuidRestorerTrace.log(
                    "controller",
                    "completeLogin autoResolvePremiumConflict requestedName=" + preparation.requestedName()
                        + " clientProfileId=" + preparation.clientProfileId()
                        + " onlineUuid=" + binding.onlineUuid
                );
                migration = migrationService.resolveSelection(server, currentConfig, binding, ResolutionScope.ALL, ResolutionPreference.PREMIUM);
                applyMigrationState(binding, migration);
                bindingStore.put(binding);
                UuidRestorerTrace.migration("controller", "completeLogin.autoResolvedMigration", migration);
                UuidRestorerTrace.binding("controller", "completeLogin.autoResolvedBinding", binding);
            }
        }
        if (migration.hasConflict() && isEnabled(currentConfig.denyOnConflict)) {
            UuidRestorerMod.LOGGER.warn(
                "Blocked premium login for {} because online and offline data conflict",
                preparation.requestedName()
            );
            UuidRestorerTrace.log("controller", "completeLogin action=deny reason=conflict");
            return LoginDecision.deny(
                "UUID restore blocked: both offline and premium player data exist. Ask an admin to resolve the conflict."
            );
        }

        UuidRestorerTrace.log("controller", "completeLogin action=applyPremium requestedName=" + preparation.requestedName() + " uuid=" + binding.onlineUuid + " canonicalName=" + binding.canonicalName);
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
        UuidRestorerTrace.log("controller", "bindNicknameDetailed nickname=" + nickname);
        LoginPreparation preparation = lookupLogin(server, nickname, null);
        UuidRestorerTrace.preparation("controller", "bindNicknameDetailed.preparation", preparation);
        if (preparation.lookupState() == PlayerBinding.LookupState.NOT_FOUND) {
            UuidRestorerTrace.log("controller", "bindNicknameDetailed result=notFound nickname=" + nickname);
            return BindAttempt.notFound();
        }
        if (preparation.lookupState() == PlayerBinding.LookupState.LOOKUP_FAILED) {
            UuidRestorerTrace.log("controller", "bindNicknameDetailed result=lookupFailed nickname=" + nickname + " details=" + preparation.lookupDetails());
            return BindAttempt.lookupFailed(preparation.lookupDetails());
        }

        UuidRestorerConfig currentConfig = config;
        PlayerBinding binding = bindingFromLookup(normalize(nickname), preparation, PlayerBinding.SOURCE_MANUAL_CONFIRMED);
        MigrationReport migration = migrationService.inspect(server, currentConfig, binding);
        applyMigrationState(binding, migration);
        bindingStore.put(binding);
        UuidRestorerTrace.binding("controller", "bindNicknameDetailed.binding", binding);
        UuidRestorerTrace.migration("controller", "bindNicknameDetailed.migration", migration);
        return BindAttempt.success(binding);
    }

    public Optional<PlayerBinding> refreshBinding(MinecraftServer server, String nickname) {
        return refreshBinding(new MinecraftServerAccess(server), nickname);
    }

    Optional<PlayerBinding> refreshBinding(ServerAccess server, String nickname) {
        Optional<PlayerBinding> existingBinding = bindingStore.get(normalize(nickname));
        if (existingBinding.isEmpty()) {
            UuidRestorerTrace.log("controller", "refreshBinding nickname=" + nickname + " result=missingBinding");
            return Optional.empty();
        }

        PlayerBinding existing = existingBinding.get();
        UuidRestorerTrace.binding("controller", "refreshBinding.existing", existing);
        LoginPreparation preparation = refreshLookup(server, nickname, existing);
        UuidRestorerTrace.preparation("controller", "refreshBinding.preparation", preparation);
        UuidRestorerConfig currentConfig = config;
        PlayerBinding refreshed = bindingForRefresh(existing, preparation);
        MigrationReport migration = migrationService.inspect(server, currentConfig, refreshed);
        applyMigrationState(refreshed, migration);
        bindingStore.put(refreshed);
        UuidRestorerTrace.binding("controller", "refreshBinding.refreshed", refreshed);
        UuidRestorerTrace.migration("controller", "refreshBinding.migration", migration);
        return Optional.of(refreshed);
    }

    public Optional<MigrationReport> migrateBinding(MinecraftServer server, String nickname) {
        return migrateBinding(new MinecraftServerAccess(server), nickname);
    }

    Optional<MigrationReport> migrateBinding(ServerAccess server, String nickname) {
        UuidRestorerConfig currentConfig = config;
        return bindingStore.get(normalize(nickname)).map(binding -> {
            UuidRestorerTrace.binding("controller", "migrateBinding.binding", binding);
            MigrationReport report = binding.hasOnlineProfile()
                ? migrationService.migrateIfSafe(server, currentConfig, binding)
                : migrationService.inspect(server, currentConfig, binding);
            if (applyMigrationState(binding, report)) {
                bindingStore.put(binding);
            }
            UuidRestorerTrace.migration("controller", "migrateBinding.report", report);
            return report;
        });
    }

    public Optional<MigrationReport> inspectBinding(MinecraftServer server, String nickname) {
        return inspectBinding(new MinecraftServerAccess(server), nickname);
    }

    Optional<MigrationReport> inspectBinding(ServerAccess server, String nickname) {
        UuidRestorerConfig currentConfig = config;
        return bindingStore.get(normalize(nickname)).map(binding -> {
            UuidRestorerTrace.binding("controller", "inspectBinding.binding", binding);
            MigrationReport report = migrationService.inspect(server, currentConfig, binding);
            UuidRestorerTrace.migration("controller", "inspectBinding.report", report);
            return report;
        });
    }

    public Optional<MigrationReport> resolveBindingConflict(MinecraftServer server, String nickname, ResolutionScope scope, ResolutionPreference preference) {
        return resolveBindingConflict(new MinecraftServerAccess(server), nickname, scope, preference);
    }

    Optional<MigrationReport> resolveBindingConflict(ServerAccess server, String nickname, ResolutionScope scope, ResolutionPreference preference) {
        UuidRestorerConfig currentConfig = config;
        return bindingStore.get(normalize(nickname)).map(binding -> {
            UuidRestorerTrace.binding("controller", "resolveBindingConflict.binding", binding);
            UuidRestorerTrace.log("controller", "resolveBindingConflict nickname=" + nickname + " scope=" + scope.serializedName() + " preference=" + preference.serializedName());
            MigrationReport report = migrationService.resolveSelection(server, currentConfig, binding, scope, preference);
            if (applyMigrationState(binding, report)) {
                bindingStore.put(binding);
            }
            UuidRestorerTrace.migration("controller", "resolveBindingConflict.report", report);
            return report;
        });
    }

    LoginPreparation lookupLogin(ServerAccess server, String requestedName, UUID clientProfileId) {
        UUID offlineUuid = Uuids.getOfflinePlayerUuid(requestedName);
        UuidRestorerTrace.log("controller", "lookupLogin requestedName=" + requestedName + " clientProfileId=" + clientProfileId + " offlineUuid=" + offlineUuid);
        try {
            Optional<ResolvedProfile> resolvedProfile = resolveProfileByName(server, requestedName);
            if (resolvedProfile.isEmpty()) {
                LoginPreparation preparation = new LoginPreparation(
                    requestedName,
                    clientProfileId,
                    offlineUuid,
                    PlayerBinding.LookupState.NOT_FOUND,
                    null,
                    null
                );
                UuidRestorerTrace.preparation("controller", "lookupLogin.result", preparation);
                return preparation;
            }
            LoginPreparation preparation = new LoginPreparation(
                requestedName,
                clientProfileId,
                offlineUuid,
                PlayerBinding.LookupState.FOUND,
                resolvedProfile.get(),
                null
            );
            UuidRestorerTrace.preparation("controller", "lookupLogin.result", preparation);
            return preparation;
        } catch (Throwable throwable) {
            UuidRestorerMod.LOGGER.warn("Premium lookup failed for {}", requestedName, throwable);
            LoginPreparation preparation = new LoginPreparation(
                requestedName,
                clientProfileId,
                offlineUuid,
                PlayerBinding.LookupState.LOOKUP_FAILED,
                null,
                summarizeThrowable(throwable)
            );
            UuidRestorerTrace.preparation("controller", "lookupLogin.result", preparation);
            UuidRestorerTrace.log("controller", "lookupLogin failed requestedName=" + requestedName, throwable);
            return preparation;
        }
    }

    private LoginPreparation refreshLookup(ServerAccess server, String nickname, PlayerBinding binding) {
        UuidRestorerTrace.binding("controller", "refreshLookup.binding", binding);
        try {
            Optional<ResolvedProfile> resolved = resolveProfileForRefresh(server, nickname, binding);
            if (resolved.isEmpty()) {
                LoginPreparation preparation = new LoginPreparation(
                    nickname,
                    binding.lastClientProfileId,
                    binding.offlineUuid,
                    PlayerBinding.LookupState.NOT_FOUND,
                    null,
                    null
                );
                UuidRestorerTrace.preparation("controller", "refreshLookup.result", preparation);
                return preparation;
            }
            LoginPreparation preparation = new LoginPreparation(
                nickname,
                binding.lastClientProfileId,
                binding.offlineUuid,
                PlayerBinding.LookupState.FOUND,
                resolved.get(),
                null
            );
            UuidRestorerTrace.preparation("controller", "refreshLookup.result", preparation);
            return preparation;
        } catch (Throwable throwable) {
            UuidRestorerMod.LOGGER.warn("Profile refresh failed for {}", nickname, throwable);
            LoginPreparation preparation = new LoginPreparation(
                nickname,
                binding.lastClientProfileId,
                binding.offlineUuid,
                PlayerBinding.LookupState.LOOKUP_FAILED,
                null,
                summarizeThrowable(throwable)
            );
            UuidRestorerTrace.preparation("controller", "refreshLookup.result", preparation);
            UuidRestorerTrace.log("controller", "refreshLookup failed nickname=" + nickname, throwable);
            return preparation;
        }
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
            bindingMode = defaults.bindingMode;
            changed = true;
        } else {
            bindingMode = bindingMode.toLowerCase(Locale.ROOT);
            if (UuidRestorerConfig.LEGACY_BINDING_MODE_SEMI_AUTO.equals(bindingMode)) {
                bindingMode = UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO;
                changed = true;
            } else if (!UuidRestorerConfig.BINDING_MODE_MANUAL_CACHED.equals(bindingMode)
                && !UuidRestorerConfig.BINDING_MODE_UNSAFE_SEMI_AUTO.equals(bindingMode)) {
                bindingMode = defaults.bindingMode;
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

        if (loaded.bindingMode != null
            && !loaded.bindingMode.isBlank()
            && !defaults.bindingMode.equalsIgnoreCase(loaded.bindingMode)) {
            UuidRestorerMod.LOGGER.warn("bindingMode '{}' is deprecated and ignored by automatic UUID restoration.", bindingMode);
        }

        return new SanitizedConfig(sanitized, changed);
    }

    private Optional<ResolvedProfile> resolveProfileByName(ServerAccess server, String nickname) {
        Optional<ResolvedProfile> resolved = server.resolveProfileByName(nickname).flatMap(profile -> enrichProfile(server, profile));
        UuidRestorerTrace.log("controller", "resolveProfileByName nickname=" + nickname + " result=" + resolved.map(UuidRestorerTrace::describeProfile).orElse("empty"));
        return resolved;
    }

    private Optional<ResolvedProfile> resolveProfileForRefresh(ServerAccess server, String nickname, PlayerBinding binding) {
        Optional<ResolvedProfile> byId = binding.hasOnlineProfile()
            ? server.resolveProfileById(binding.onlineUuid).flatMap(profile -> enrichProfile(server, profile))
            : Optional.empty();
        Optional<ResolvedProfile> resolved = byId
            .or(() -> resolveProfileByName(server, binding.canonicalName))
            .or(() -> resolveProfileByName(server, nickname));
        UuidRestorerTrace.log("controller", "resolveProfileForRefresh nickname=" + nickname + " byId=" + byId.map(UuidRestorerTrace::describeProfile).orElse("empty") + " result=" + resolved.map(UuidRestorerTrace::describeProfile).orElse("empty"));
        return resolved;
    }

    private Optional<ResolvedProfile> enrichProfile(ServerAccess server, ResolvedProfile profile) {
        ResolvedProfile current = profile;
        if (!current.hasTextures()) {
            current = server.fetchTextures(current).orElse(current);
        }
        Optional<ResolvedProfile> resolved = Optional.ofNullable(current.uuid() == null ? null : current);
        UuidRestorerTrace.log("controller", "enrichProfile input=" + UuidRestorerTrace.describeProfile(profile) + " output=" + resolved.map(UuidRestorerTrace::describeProfile).orElse("empty"));
        return resolved;
    }

    private PlayerBinding bindingForLogin(String lookupKey, LoginPreparation preparation, Optional<PlayerBinding> existingBinding) {
        if (preparation.hasOnlineProfile()) {
            return bindingFromLookup(
                lookupKey,
                preparation,
                existingBinding.map(PlayerBinding::normalizedBindingSource).orElse(PlayerBinding.SOURCE_LEGACY_AUTO)
            );
        }

        return existingBinding
            .filter(existing -> shouldUseStoredBindingForLogin(preparation, existing))
            .map(existing -> bindingFromExisting(lookupKey, preparation, existing))
            .orElseGet(() -> bindingFromLookup(lookupKey, preparation, PlayerBinding.SOURCE_LEGACY_AUTO));
    }

    private PlayerBinding bindingForRefresh(PlayerBinding existing, LoginPreparation preparation) {
        if (preparation.hasOnlineProfile()) {
            return bindingFromLookup(existing.lookupKey, preparation, existing.normalizedBindingSource());
        }
        if (existing.hasOnlineProfile()) {
            return bindingFromExisting(existing.lookupKey, preparation, existing);
        }
        return bindingFromLookup(existing.lookupKey, preparation, existing.normalizedBindingSource());
    }

    private PlayerBinding bindingFromLookup(String lookupKey, LoginPreparation preparation, String bindingSource) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = lookupKey;
        binding.canonicalName = preparation.hasOnlineProfile()
            ? preparation.resolvedProfile().name()
            : preparation.requestedName();
        binding.onlineUuid = preparation.hasOnlineProfile() ? preparation.resolvedProfile().uuid() : null;
        binding.offlineUuid = preparation.offlineUuid();
        binding.texturesValue = preparation.hasOnlineProfile() ? preparation.resolvedProfile().texturesValue() : null;
        binding.texturesSignature = preparation.hasOnlineProfile() ? preparation.resolvedProfile().texturesSignature() : null;
        binding.lastResolvedAt = Instant.now().toString();
        binding.lastClientProfileId = preparation.clientProfileId();
        binding.migrationState = "pending";
        binding.conflictState = "none";
        binding.bindingSource = bindingSource;
        binding.lookupState = preparation.lookupState();
        return binding;
    }

    private PlayerBinding bindingFromExisting(String lookupKey, LoginPreparation preparation, PlayerBinding existing) {
        PlayerBinding binding = existing.copy();
        binding.lookupKey = lookupKey;
        binding.canonicalName = isBlank(binding.canonicalName) ? preparation.requestedName() : binding.canonicalName;
        binding.offlineUuid = preparation.offlineUuid();
        binding.lastResolvedAt = Instant.now().toString();
        binding.lastClientProfileId = preparation.clientProfileId();
        binding.migrationState = isBlank(binding.migrationState) ? "pending" : binding.migrationState;
        binding.conflictState = isBlank(binding.conflictState) ? "none" : binding.conflictState;
        binding.lookupState = PlayerBinding.LookupState.FOUND;
        return binding;
    }

    private boolean applyMigrationState(PlayerBinding binding, MigrationReport report) {
        String nextMigrationState = report.migrationState();
        String nextConflictState = report.conflictState();
        boolean changed = !Objects.equals(binding.migrationState, nextMigrationState)
            || !Objects.equals(binding.conflictState, nextConflictState);
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

    private static boolean shouldUseStoredBindingForLogin(LoginPreparation preparation, PlayerBinding existing) {
        if (!existing.hasOnlineProfile()) {
            return false;
        }
        if (!Objects.equals(existing.offlineUuid, preparation.offlineUuid())) {
            return false;
        }
        return existing.isTrusted() || preparation.lookupState() == PlayerBinding.LookupState.LOOKUP_FAILED;
    }

    private static boolean shouldAutoPreferPremiumOnLogin(LoginPreparation preparation, PlayerBinding binding) {
        if (!binding.isTrusted() || !binding.hasOnlineProfile()) {
            return false;
        }
        return Objects.equals(preparation.clientProfileId(), binding.onlineUuid);
    }

    private static boolean shouldPassThroughForServer(ServerAccess server) {
        return server.isOnlineMode() && server.isDedicatedServer();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
