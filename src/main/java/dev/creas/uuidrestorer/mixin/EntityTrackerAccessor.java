package dev.creas.uuidrestorer.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public interface EntityTrackerAccessor {
    @Invoker("stopTracking")
    void uuidrestorer$stopTracking(ServerPlayerEntity player);

    @Invoker("updateTrackedStatus")
    void uuidrestorer$updateTrackedStatus(ServerPlayerEntity player);
}
