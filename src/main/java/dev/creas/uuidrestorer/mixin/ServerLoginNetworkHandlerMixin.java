package dev.creas.uuidrestorer.mixin;

import com.mojang.authlib.GameProfile;
import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.service.LoginDecision;
import dev.creas.uuidrestorer.service.LoginPreparation;
import dev.creas.uuidrestorer.service.UuidRestorerTrace;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Mixin(net.minecraft.server.network.ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow private int loginTicks;
    @Shadow private GameProfile profile;
    @Shadow String profileName;
    @Shadow public abstract void disconnect(Text reason);

    @Unique
    private LoginDecision uuidrestorer$loginDecision = LoginDecision.passThrough();

    @Unique
    private CompletableFuture<LoginPreparation> uuidrestorer$pendingPreparation;

    @Unique
    private boolean uuidrestorer$preparationApplied;

    @Inject(method = "onHello", at = @At("HEAD"))
    private void uuidrestorer$startPreparation(LoginHelloC2SPacket packet, CallbackInfo ci) {
        UuidRestorerTrace.log(
            "login-mixin",
            "onHello name=" + packet.name()
                + " profileId=" + packet.profileId()
                + " onlineMode=" + this.server.isOnlineMode()
                + " dedicated=" + uuidrestorer$isDedicatedServer()
                + " currentProfile=" + UuidRestorerTrace.describeGameProfile(this.profile)
                + " profileName=" + this.profileName
        );
        if (this.server.isOnlineMode() && uuidrestorer$isDedicatedServer()) {
            this.uuidrestorer$pendingPreparation = null;
            this.uuidrestorer$preparationApplied = false;
            this.uuidrestorer$loginDecision = LoginDecision.passThrough();
            UuidRestorerTrace.decision("login-mixin", "onHello.decision", this.uuidrestorer$loginDecision);
            return;
        }

        this.uuidrestorer$pendingPreparation = UuidRestorerMod.controller().prepareLoginAsync(this.server, packet.name(), packet.profileId());
        this.uuidrestorer$preparationApplied = false;
        this.uuidrestorer$loginDecision = LoginDecision.passThrough();
        UuidRestorerTrace.log("login-mixin", "onHello started async preparation pending=" + (this.uuidrestorer$pendingPreparation != null));
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void uuidrestorer$gateTickUntilPrepared(CallbackInfo ci) {
        if (this.uuidrestorer$pendingPreparation == null || this.uuidrestorer$preparationApplied) {
            return;
        }

        if (!this.uuidrestorer$pendingPreparation.isDone()) {
            if (this.loginTicks == 0 || this.loginTicks % 20 == 0) {
                UuidRestorerTrace.log("login-mixin", "tick waiting loginTicks=" + this.loginTicks + " profileName=" + this.profileName);
            }
            uuidrestorer$advanceLoginTick();
            ci.cancel();
            return;
        }

        LoginPreparation preparation;
        try {
            preparation = this.uuidrestorer$pendingPreparation.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            UuidRestorerMod.LOGGER.error("UUID Restorer login preparation failed", cause);
            UuidRestorerTrace.log("login-mixin", "tick join failed profileName=" + this.profileName, cause);
            this.disconnect(Text.literal("UUID restore failed during login. See server log."));
            this.uuidrestorer$pendingPreparation = null;
            this.uuidrestorer$preparationApplied = true;
            ci.cancel();
            return;
        }

        UuidRestorerTrace.preparation("login-mixin", "tick.preparation", preparation);
        this.uuidrestorer$loginDecision = UuidRestorerMod.controller().completeLogin(this.server, preparation);
        UuidRestorerTrace.decision("login-mixin", "tick.decision", this.uuidrestorer$loginDecision);
        this.uuidrestorer$preparationApplied = true;
        this.uuidrestorer$pendingPreparation = null;

        if (this.uuidrestorer$loginDecision.action() == LoginDecision.Action.DENY) {
            UuidRestorerTrace.log("login-mixin", "tick disconnect reason=" + this.uuidrestorer$loginDecision.message());
            this.disconnect(Text.literal(this.uuidrestorer$loginDecision.message()));
            ci.cancel();
            return;
        }

        uuidrestorer$applyReplacementProfile();
    }

    @Inject(method = "sendSuccessPacket", at = @At("HEAD"), cancellable = true)
    private void uuidrestorer$beforeSendSuccessPacket(GameProfile profile, CallbackInfo ci) {
        UuidRestorerTrace.log(
            "login-mixin",
            "sendSuccessPacket inputProfile=" + UuidRestorerTrace.describeGameProfile(profile)
                + " storedProfile=" + UuidRestorerTrace.describeGameProfile(this.profile)
        );
        if (this.uuidrestorer$loginDecision.action() == LoginDecision.Action.DENY) {
            UuidRestorerTrace.log("login-mixin", "sendSuccessPacket disconnect reason=" + this.uuidrestorer$loginDecision.message());
            this.disconnect(Text.literal(this.uuidrestorer$loginDecision.message()));
            ci.cancel();
        }
    }

    @Inject(method = "onEnterConfiguration", at = @At("HEAD"))
    private void uuidrestorer$beforeEnterConfiguration(CallbackInfo ci) {
        UuidRestorerTrace.log(
            "login-mixin",
            "onEnterConfiguration profileBefore=" + UuidRestorerTrace.describeGameProfile(this.profile)
                + " profileName=" + this.profileName
        );
        uuidrestorer$applyReplacementProfile();
    }

    @Unique
    private GameProfile uuidrestorer$applyReplacementProfile() {
        if (this.uuidrestorer$loginDecision.action() != LoginDecision.Action.APPLY_PREMIUM) {
            UuidRestorerTrace.log("login-mixin", "applyReplacementProfile skipped action=" + this.uuidrestorer$loginDecision.action());
            return null;
        }

        GameProfile replacementProfile = this.uuidrestorer$loginDecision.replacementProfile();
        if (replacementProfile == null) {
            UuidRestorerTrace.log("login-mixin", "applyReplacementProfile skipped replacementProfile=null");
            return null;
        }

        UuidRestorerTrace.log(
            "login-mixin",
            "applyReplacementProfile before profile=" + UuidRestorerTrace.describeGameProfile(this.profile)
                + " profileName=" + this.profileName
                + " replacement=" + UuidRestorerTrace.describeGameProfile(replacementProfile)
                + " replacementName=" + this.uuidrestorer$loginDecision.replacementProfileName()
        );
        this.profile = replacementProfile;
        this.profileName = this.uuidrestorer$loginDecision.replacementProfileName();
        UuidRestorerTrace.log(
            "login-mixin",
            "applyReplacementProfile after profile=" + UuidRestorerTrace.describeGameProfile(this.profile)
                + " profileName=" + this.profileName
        );
        return replacementProfile;
    }

    @Unique
    private void uuidrestorer$advanceLoginTick() {
        this.loginTicks++;
        if (this.loginTicks == 600) {
            UuidRestorerTrace.log("login-mixin", "advanceLoginTick disconnect slow_login");
            this.disconnect(Text.translatable("multiplayer.disconnect.slow_login"));
        }
    }

    @Unique
    private boolean uuidrestorer$isDedicatedServer() {
        return this.server.getClass().getName().contains(".server.dedicated.");
    }
}
