package dev.creas.uuidrestorer.data;

import java.util.UUID;

public final class PlayerBinding {
    public static final String SOURCE_MANUAL_CONFIRMED = "manual_confirmed";
    public static final String SOURCE_LEGACY_AUTO = "legacy_auto";

    public String canonicalName;
    public String lookupKey;
    public UUID onlineUuid;
    public UUID offlineUuid;
    public String texturesValue;
    public String texturesSignature;
    public String lastResolvedAt;
    public UUID lastClientProfileId;
    public String migrationState;
    public String conflictState;
    public String bindingSource;

    public boolean hasTextures() {
        return texturesValue != null && !texturesValue.isBlank();
    }

    public String normalizedBindingSource() {
        if (SOURCE_MANUAL_CONFIRMED.equals(bindingSource)) {
            return SOURCE_MANUAL_CONFIRMED;
        }
        return SOURCE_LEGACY_AUTO;
    }

    public boolean isTrusted() {
        return SOURCE_MANUAL_CONFIRMED.equals(normalizedBindingSource());
    }

    public String trustLabel() {
        return isTrusted() ? "trusted" : "insecure";
    }

    public PlayerBinding copy() {
        PlayerBinding copy = new PlayerBinding();
        copy.canonicalName = canonicalName;
        copy.lookupKey = lookupKey;
        copy.onlineUuid = onlineUuid;
        copy.offlineUuid = offlineUuid;
        copy.texturesValue = texturesValue;
        copy.texturesSignature = texturesSignature;
        copy.lastResolvedAt = lastResolvedAt;
        copy.lastClientProfileId = lastClientProfileId;
        copy.migrationState = migrationState;
        copy.conflictState = conflictState;
        copy.bindingSource = bindingSource;
        return copy;
    }
}
