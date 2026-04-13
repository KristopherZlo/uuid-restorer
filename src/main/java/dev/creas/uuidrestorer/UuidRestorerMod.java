package dev.creas.uuidrestorer;

import dev.creas.uuidrestorer.command.UuidRestorerCommands;
import dev.creas.uuidrestorer.service.UuidRestorerTrace;
import dev.creas.uuidrestorer.smoke.SmokeLaunchSupport;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class UuidRestorerMod implements ModInitializer {
    public static final String MOD_ID = "uuidrestorer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String UNKNOWN_VERSION = "unknown";

    private static UuidRestorerController controller;

    @Override
    public void onInitialize() {
        Path baseDirectory = FabricLoader.getInstance().getConfigDir().resolve("uuid-restorer");
        UuidRestorerTrace.initialize(baseDirectory);
        controller = new UuidRestorerController(baseDirectory);
        controller.reload();
        SmokeLaunchSupport.registerServerHook();

        CommandRegistrationCallback.EVENT.register(UuidRestorerCommands::register);
        LOGGER.info("UUID Restorer initialized");
        UuidRestorerTrace.log("mod", "initialized version=" + version() + " baseDirectory=" + baseDirectory.toAbsolutePath());
    }

    public static UuidRestorerController controller() {
        return controller;
    }

    public static String version() {
        return FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse(UNKNOWN_VERSION);
    }
}
