package dev.creas.uuidrestorer.runtime;

import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthlibCompatTest {
    @Test
    void readsModernStyleProfileAccessors() {
        UUID uuid = UUID.randomUUID();
        ModernProfile profile = new ModernProfile(
            uuid,
            "Alice",
            Map.of("textures", List.of(new ModernProperty("textures", "value", "sig")))
        );

        ResolvedProfile resolvedProfile = AuthlibCompat.toResolvedProfile(profile);

        assertEquals(uuid, resolvedProfile.uuid());
        assertEquals("Alice", resolvedProfile.name());
        assertEquals("value", resolvedProfile.texturesValue());
        assertEquals("sig", resolvedProfile.texturesSignature());
    }

    @Test
    void readsLegacyStyleProfileAccessors() {
        UUID uuid = UUID.randomUUID();
        LegacyProfile profile = new LegacyProfile(
            uuid,
            "Bob",
            Map.of("textures", List.of(new LegacyProperty("textures", "legacy-value", "legacy-sig")))
        );

        ResolvedProfile resolvedProfile = AuthlibCompat.toResolvedProfile(profile);

        assertEquals(uuid, resolvedProfile.uuid());
        assertEquals("Bob", resolvedProfile.name());
        assertEquals("legacy-value", resolvedProfile.texturesValue());
        assertEquals("legacy-sig", resolvedProfile.texturesSignature());
    }

    @Test
    void createsRoundTripGameProfileForCurrentAuthlib() {
        UUID uuid = UUID.randomUUID();
        GameProfile profile = AuthlibCompat.createGameProfile(uuid, "Carol", "value", "sig");

        ResolvedProfile resolvedProfile = AuthlibCompat.toResolvedProfile(profile);

        assertEquals(uuid, resolvedProfile.uuid());
        assertEquals("Carol", resolvedProfile.name());
        assertTrue(resolvedProfile.hasTextures());
        assertEquals("value", resolvedProfile.texturesValue());
        assertEquals("sig", resolvedProfile.texturesSignature());
    }

    @Test
    void createsRoundTripGameProfileWithoutTextures() {
        UUID uuid = UUID.randomUUID();
        GameProfile profile = AuthlibCompat.createGameProfile(uuid, "Dave", null, null);

        ResolvedProfile resolvedProfile = AuthlibCompat.toResolvedProfile(profile);

        assertEquals(uuid, resolvedProfile.uuid());
        assertEquals("Dave", resolvedProfile.name());
        assertFalse(resolvedProfile.hasTextures());
    }

    private record ModernProfile(UUID id, String name, Map<String, List<ModernProperty>> properties) {
    }

    private record ModernProperty(String name, String value, String signature) {
    }

    private static final class LegacyProfile {
        private final UUID id;
        private final String name;
        private final Map<String, List<LegacyProperty>> properties;

        private LegacyProfile(UUID id, String name, Map<String, List<LegacyProperty>> properties) {
            this.id = id;
            this.name = name;
            this.properties = properties;
        }

        public UUID getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Map<String, List<LegacyProperty>> getProperties() {
            return properties;
        }
    }

    private static final class LegacyProperty {
        private final String name;
        private final String value;
        private final String signature;

        private LegacyProperty(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getSignature() {
            return signature;
        }
    }
}
