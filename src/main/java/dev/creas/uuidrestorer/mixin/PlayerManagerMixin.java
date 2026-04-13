package dev.creas.uuidrestorer.mixin;

import dev.creas.uuidrestorer.service.PlayerProfileService;
import dev.creas.uuidrestorer.service.UuidRestorerTrace;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Inject(method = "onPlayerConnect", at = @At("HEAD"))
    private void uuidrestorer$applyStoredProfile(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        UuidRestorerTrace.log(
            "player-manager-mixin",
            "onPlayerConnect.head name=" + player.getNameForScoreboard()
                + " uuid=" + player.getUuid()
                + " profile=" + UuidRestorerTrace.describeGameProfile(player.getGameProfile())
        );
        PlayerProfileService.applyStoredTextures(player);
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void uuidrestorer$refreshStoredProfile(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        UuidRestorerTrace.log(
            "player-manager-mixin",
            "onPlayerConnect.tail beforeRefresh name=" + player.getNameForScoreboard()
                + " uuid=" + player.getUuid()
                + " profile=" + UuidRestorerTrace.describeGameProfile(player.getGameProfile())
        );
        PlayerProfileService.refreshStoredTextures(player);
        UuidRestorerTrace.log(
            "player-manager-mixin",
            "onPlayerConnect.tail afterRefresh name=" + player.getNameForScoreboard()
                + " uuid=" + player.getUuid()
                + " profile=" + UuidRestorerTrace.describeGameProfile(player.getGameProfile())
        );
    }
}
