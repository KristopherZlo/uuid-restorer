package dev.creas.uuidrestorer.data;

import java.util.UUID;

public final class PlayerBinding {
    public static final String SOURCE_MANUAL_CONFIRMED = "manual_confirmed";
    public static final String SOURCE_LEGACY_AUTO = "legacy_auto";

    public enum LookupState {
        FOUND,
        NOT_FOUND,
        LOOKUP_FAILED
    }

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
    public LookupState lookupState;

    public boolean hasOnlineProfile() {
        return normalizedLookupState() == LookupState.FOUND && onlineUuid != null;
    }

    public boolean hasTextures() {
        return hasOnlineProfile() && texturesValue != null && !texturesValue.isBlank();
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

    public LookupState normalizedLookupState() {
        if (lookupState == LookupState.FOUND && onlineUuid == null) {
            return LookupState.NOT_FOUND;
        }
        if (lookupState != null) {
            return lookupState;
        }
        return onlineUuid != null ? LookupState.FOUND : LookupState.NOT_FOUND;
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
        copy.lookupState = lookupState;
        return copy;
    }
}
