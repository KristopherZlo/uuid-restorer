package dev.creas.uuidrestorer.service;

import com.mojang.authlib.GameProfile;

public record LoginDecision(Action action, GameProfile replacementProfile, String replacementProfileName, String message) {
    public enum Action {
        PASS_THROUGH,
        APPLY_PREMIUM,
        DENY
    }

    public static LoginDecision passThrough() {
        return new LoginDecision(Action.PASS_THROUGH, null, null, null);
    }

    public static LoginDecision apply(GameProfile profile, String profileName) {
        return new LoginDecision(Action.APPLY_PREMIUM, profile, profileName, null);
    }

    public static LoginDecision deny(String message) {
        return new LoginDecision(Action.DENY, null, null, message);
    }
}
