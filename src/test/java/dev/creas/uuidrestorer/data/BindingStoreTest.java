package dev.creas.uuidrestorer.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void loadCreatesMissingBindingsFileAndStoresEntriesCaseInsensitively() throws Exception {
        Path file = tempDir.resolve("bindings.json");
        BindingStore store = new BindingStore(file);

        store.load();

        assertTrue(Files.exists(file));

        PlayerBinding binding = createBinding("Alice", PlayerBinding.SOURCE_MANUAL_CONFIRMED);
        store.put(binding);

        PlayerBinding loaded = store.get("aLiCe").orElseThrow();
        assertEquals(binding.onlineUuid, loaded.onlineUuid);
        assertEquals(binding.offlineUuid, loaded.offlineUuid);
        assertEquals(PlayerBinding.SOURCE_MANUAL_CONFIRMED, loaded.normalizedBindingSource());
    }

    @Test
    void loadMigratesLegacyBindingsAndDropsMalformedEntries() throws Exception {
        Path file = tempDir.resolve("bindings.json");
        Files.writeString(file, """
            {
              "Alice": {
                "lookupKey": "Alice",
                "canonicalName": "Alice",
                "onlineUuid": "11111111-1111-1111-1111-111111111111",
                "offlineUuid": "22222222-2222-2222-2222-222222222222"
              },
              "Broken": {
                "canonicalName": "Broken",
                "onlineUuid": "33333333-3333-3333-3333-333333333333"
              }
            }
            """);

        BindingStore store = new BindingStore(file);
        store.load();

        PlayerBinding loaded = store.get("alice").orElseThrow();
        assertEquals("alice", loaded.lookupKey);
        assertEquals(PlayerBinding.SOURCE_LEGACY_AUTO, loaded.normalizedBindingSource());
        assertFalse(store.get("broken").isPresent());
        assertTrue(Files.readString(file).contains("\"bindingSource\": \"legacy_auto\""));
    }

    @Test
    void loadQuarantinesBrokenBindingsFileAndContinuesWithEmptyStore() throws Exception {
        Path file = tempDir.resolve("bindings.json");
        Files.writeString(file, "{broken");

        BindingStore store = new BindingStore(file);
        store.load();

        assertTrue(Files.exists(file));
        assertTrue(Files.list(tempDir).anyMatch(path -> path.getFileName().toString().startsWith("bindings.broken-")));
        assertFalse(store.get("alice").isPresent());
    }

    @Test
    void getReturnsCopyAndRemoveDeletesEntry() {
        BindingStore store = new BindingStore(tempDir.resolve("bindings.json"));
        store.load();

        PlayerBinding binding = createBinding("Bob", PlayerBinding.SOURCE_MANUAL_CONFIRMED);
        store.put(binding);

        PlayerBinding first = store.get("bob").orElseThrow();
        PlayerBinding second = store.get("bob").orElseThrow();

        assertNotSame(first, second);

        first.canonicalName = "Changed";
        assertEquals("Bob", store.get("bob").orElseThrow().canonicalName);

        assertTrue(store.remove("BOB"));
        assertFalse(store.get("bob").isPresent());
    }

    private static PlayerBinding createBinding(String name, String source) {
        PlayerBinding binding = new PlayerBinding();
        binding.lookupKey = name;
        binding.canonicalName = name;
        binding.onlineUuid = UUID.randomUUID();
        binding.offlineUuid = UUID.randomUUID();
        binding.bindingSource = source;
        binding.migrationState = "pending";
        binding.conflictState = "none";
        binding.texturesValue = "value";
        binding.texturesSignature = "signature";
        return binding;
    }
}
