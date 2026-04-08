package dev.creas.uuidrestorer.runtime;

import java.util.UUID;

public record ResolvedProfile(UUID uuid, String name, String texturesValue, String texturesSignature) {
    public boolean hasTextures() {
        return texturesValue != null && !texturesValue.isBlank();
    }
}
