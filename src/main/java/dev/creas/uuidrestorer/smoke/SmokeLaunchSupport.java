package dev.creas.uuidrestorer.smoke;

import dev.creas.uuidrestorer.UuidRestorerMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class SmokeLaunchSupport {
    public static final String SERVER_SMOKE_PROPERTY = "uuidrestorer.smoke.server";
    public static final String CLIENT_SMOKE_PROPERTY = "uuidrestorer.smoke.client";

    private static boolean serverHookRegistered;
    private static boolean clientHookRegistered;

    private SmokeLaunchSupport() {
    }

    public static void registerServerHook() {
        if (serverHookRegistered || !Boolean.getBoolean(SERVER_SMOKE_PROPERTY)) {
            return;
        }

        serverHookRegistered = true;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            UuidRestorerMod.LOGGER.info("UUID Restorer smoke server launch reached SERVER_STARTED, stopping automatically");
            server.execute(() -> server.stop(false));
        });
    }

    public static void registerClientHook() {
        if (clientHookRegistered || !Boolean.getBoolean(CLIENT_SMOKE_PROPERTY)) {
            return;
        }

        clientHookRegistered = true;
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            UuidRestorerMod.LOGGER.info("UUID Restorer smoke client launch reached CLIENT_STARTED, scheduling shutdown");
            client.execute(client::scheduleStop);
        });
    }
}
