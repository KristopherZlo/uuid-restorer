package dev.creas.uuidrestorer.service;

import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.runtime.ResolvedProfile;

import java.util.UUID;

public record LoginPreparation(
    String requestedName,
    UUID clientProfileId,
    UUID offlineUuid,
    PlayerBinding.LookupState lookupState,
    ResolvedProfile resolvedProfile,
    String lookupDetails
) {
    public boolean hasOnlineProfile() {
        return lookupState == PlayerBinding.LookupState.FOUND
            && resolvedProfile != null
            && resolvedProfile.uuid() != null;
    }
}
