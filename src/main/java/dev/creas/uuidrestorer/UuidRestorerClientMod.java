package dev.creas.uuidrestorer;

import dev.creas.uuidrestorer.smoke.SmokeLaunchSupport;
import net.fabricmc.api.ClientModInitializer;

public final class UuidRestorerClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SmokeLaunchSupport.registerClientHook();
    }
}
