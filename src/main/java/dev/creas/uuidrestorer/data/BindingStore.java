package dev.creas.uuidrestorer.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.creas.uuidrestorer.UuidRestorerMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BindingStore {
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, PlayerBinding>>() { }.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private Map<String, PlayerBinding> bindings = new LinkedHashMap<>();

    public BindingStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                bindings = new LinkedHashMap<>();
                save();
                return;
            }

            Map<String, PlayerBinding> loaded;
            try (Reader reader = Files.newBufferedReader(file)) {
                loaded = gson.fromJson(reader, MAP_TYPE);
            }

            SanitizedBindings sanitized = sanitizeBindings(loaded);
            bindings = sanitized.bindings();
            if (sanitized.changed()) {
                save();
            }
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            UuidRestorerMod.LOGGER.error("Failed to load bindings from {}", file, exception);
            quarantineBrokenFile();
            bindings = new LinkedHashMap<>();
            save();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tempFile)) {
                gson.toJson(bindings, MAP_TYPE, writer);
            }
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            UuidRestorerMod.LOGGER.error("Failed to save bindings to {}", file, exception);
        }
    }

    public synchronized Optional<PlayerBinding> get(String lookupKey) {
        PlayerBinding binding = bindings.get(normalize(lookupKey));
        return Optional.ofNullable(binding == null ? null : binding.copy());
    }

    public synchronized void put(PlayerBinding binding) {
        bindings.put(normalize(binding.lookupKey), binding.copy());
        save();
    }

    public synchronized boolean remove(String lookupKey) {
        boolean removed = bindings.remove(normalize(lookupKey)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private SanitizedBindings sanitizeBindings(Map<String, PlayerBinding> loaded) {
        if (loaded == null || loaded.isEmpty()) {
            return new SanitizedBindings(new LinkedHashMap<>(), true);
        }

        boolean changed = false;
        Map<String, PlayerBinding> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerBinding> entry : loaded.entrySet()) {
            PlayerBinding binding = sanitizeBinding(entry.getValue());
            if (binding == null) {
                changed = true;
                continue;
            }

            String normalizedKey = normalize(binding.lookupKey);
            if (!normalizedKey.equals(binding.lookupKey) || !normalizedKey.equals(entry.getKey())) {
                changed = true;
            }
            binding.lookupKey = normalizedKey;
            sanitized.put(normalizedKey, binding);
        }
        return new SanitizedBindings(sanitized, changed);
    }

    private PlayerBinding sanitizeBinding(PlayerBinding binding) {
        if (binding == null) {
            return null;
        }
        if (isBlank(binding.lookupKey) || isBlank(binding.canonicalName) || binding.onlineUuid == null || binding.offlineUuid == null) {
            return null;
        }

        PlayerBinding sanitized = binding.copy();
        sanitized.lookupKey = normalize(sanitized.lookupKey);
        sanitized.bindingSource = sanitized.normalizedBindingSource();
        if (isBlank(sanitized.migrationState)) {
            sanitized.migrationState = "pending";
        }
        if (isBlank(sanitized.conflictState)) {
            sanitized.conflictState = "none";
        }
        return sanitized;
    }

    private void quarantineBrokenFile() {
        if (!Files.exists(file)) {
            return;
        }

        Path brokenFile = file.resolveSibling(brokenFileName());
        try {
            Files.move(file, brokenFile, StandardCopyOption.REPLACE_EXISTING);
            UuidRestorerMod.LOGGER.warn("Moved broken bindings file to {}", brokenFile);
        } catch (IOException moveException) {
            UuidRestorerMod.LOGGER.error("Failed to move broken bindings file {}", file, moveException);
        }
    }

    private String brokenFileName() {
        String name = file.getFileName().toString();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        return name + ".broken-" + Instant.now().toString().replace(":", "-") + ".json";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SanitizedBindings(Map<String, PlayerBinding> bindings, boolean changed) {
    }
}
