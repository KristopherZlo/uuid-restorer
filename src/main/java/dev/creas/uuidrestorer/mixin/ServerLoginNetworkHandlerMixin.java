package dev.creas.uuidrestorer.mixin;

import com.mojang.authlib.GameProfile;
import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.service.LoginDecision;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.server.network.ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow private GameProfile profile;
    @Shadow String profileName;
    @Shadow public abstract void disconnect(Text reason);

    @Unique
    private LoginDecision uuidrestorer$loginDecision = LoginDecision.passThrough();

    @Inject(method = "onHello", at = @At("TAIL"))
    private void uuidrestorer$resolvePremiumProfile(LoginHelloC2SPacket packet, CallbackInfo ci) {
        this.uuidrestorer$loginDecision = UuidRestorerMod.controller().prepareLogin(this.server, packet.name(), packet.profileId());
        if (this.uuidrestorer$loginDecision.action() == LoginDecision.Action.APPLY_PREMIUM) {
            this.profile = this.uuidrestorer$loginDecision.replacementProfile();
            this.profileName = this.uuidrestorer$loginDecision.replacementProfileName();
        }
    }

    @Inject(method = "sendSuccessPacket", at = @At("HEAD"), cancellable = true)
    private void uuidrestorer$denyConflictedLogin(GameProfile profile, CallbackInfo ci) {
        if (this.uuidrestorer$loginDecision.action() == LoginDecision.Action.DENY) {
            this.disconnect(Text.literal(this.uuidrestorer$loginDecision.message()));
            ci.cancel();
        }
    }
}
