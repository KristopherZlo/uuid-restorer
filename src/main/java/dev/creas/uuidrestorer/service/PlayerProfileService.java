package dev.creas.uuidrestorer.service;

import com.mojang.authlib.GameProfile;
import dev.creas.uuidrestorer.UuidRestorerMod;
import dev.creas.uuidrestorer.data.PlayerBinding;
import dev.creas.uuidrestorer.mixin.EntityTrackerAccessor;
import dev.creas.uuidrestorer.mixin.PlayerEntityAccessor;
import dev.creas.uuidrestorer.mixin.ServerChunkLoadingManagerAccessor;
import dev.creas.uuidrestorer.mixin.ServerChunkManagerAccessor;
import dev.creas.uuidrestorer.runtime.AuthlibCompat;
import dev.creas.uuidrestorer.runtime.ResolvedProfile;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PlayerProfileService {
    private PlayerProfileService() {
    }

    public static void applyStoredTextures(ServerPlayerEntity player) {
        try {
            UuidRestorerTrace.log(
                "profile-service",
                "applyStoredTextures player=" + player.getNameForScoreboard()
                    + " currentProfile=" + UuidRestorerTrace.describeGameProfile(player.getGameProfile())
                    + " playerUuid=" + player.getUuid()
            );
            resolveBinding(player).ifPresent(binding -> {
                GameProfile currentProfile = player.getGameProfile();
                if (matchesBinding(currentProfile, binding)) {
                    UuidRestorerTrace.log("profile-service", "applyStoredTextures skipped reason=alreadyMatches binding=" + UuidRestorerTrace.describeBinding(binding));
                    return;
                }

                GameProfile replacement = AuthlibCompat.createGameProfile(
                    binding.onlineUuid,
                    binding.canonicalName,
                    binding.texturesValue,
                    binding.texturesSignature
                );
                UuidRestorerTrace.log(
                    "profile-service",
                    "applyStoredTextures replacing current=" + UuidRestorerTrace.describeGameProfile(currentProfile)
                        + " replacement=" + UuidRestorerTrace.describeGameProfile(replacement)
                        + " binding=" + UuidRestorerTrace.describeBinding(binding)
                );
                ((PlayerEntityAccessor) player).uuidrestorer$setGameProfile(replacement);
            });
        } catch (Throwable throwable) {
            UuidRestorerTrace.log("profile-service", "applyStoredTextures failed player=" + player.getNameForScoreboard(), throwable);
            UuidRestorerMod.LOGGER.warn("Failed to apply stored textures for {}", player.getNameForScoreboard(), throwable);
        }
    }

    public static void refreshStoredTextures(ServerPlayerEntity player) {
        try {
            UuidRestorerTrace.log(
                "profile-service",
                "refreshStoredTextures player=" + player.getNameForScoreboard()
                    + " currentProfile=" + UuidRestorerTrace.describeGameProfile(player.getGameProfile())
                    + " playerUuid=" + player.getUuid()
            );
            Optional<PlayerBinding> binding = resolveBinding(player);
            if (binding.isEmpty()) {
                UuidRestorerTrace.log("profile-service", "refreshStoredTextures skipped reason=noResolvedBinding player=" + player.getNameForScoreboard());
                return;
            }

            applyStoredTextures(player);
            if (player.networkHandler == null) {
                UuidRestorerTrace.log("profile-service", "refreshStoredTextures skipped reason=noNetworkHandler player=" + player.getNameForScoreboard());
                return;
            }

            ServerWorld world = player.getEntityWorld();
            PlayerManager playerManager = world.getServer().getPlayerManager();

            playerManager.sendToAll(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
            playerManager.sendToAll(new PlayerListS2CPacket(
                EnumSet.allOf(PlayerListS2CPacket.Action.class),
                List.of(player)
            ));

            refreshTrackedEntity(playerManager, world, player);

            player.networkHandler.sendPacket(new PlayerRespawnS2CPacket(
                player.createCommonPlayerSpawnInfo(world),
                PlayerRespawnS2CPacket.KEEP_ALL
            ));
            player.networkHandler.sendPacket(new GameStateChangeS2CPacket(
                GameStateChangeS2CPacket.INITIAL_CHUNKS_COMING,
                0.0F
            ));
            player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));

            if (player.getVehicle() != null) {
                player.networkHandler.sendPacket(new EntityPassengersSetS2CPacket(player.getVehicle()));
            }
            if (!player.getPassengerList().isEmpty()) {
                player.networkHandler.sendPacket(new EntityPassengersSetS2CPacket(player));
            }

            player.sendAbilitiesUpdate();
            playerManager.sendPlayerStatus(player);
            playerManager.sendWorldInfo(player, world);
            playerManager.sendStatusEffects(player);
            UuidRestorerTrace.log(
                "profile-service",
                "refreshStoredTextures completed player=" + player.getNameForScoreboard()
                    + " finalProfile=" + UuidRestorerTrace.describeGameProfile(player.getGameProfile())
            );
        } catch (Throwable throwable) {
            UuidRestorerTrace.log("profile-service", "refreshStoredTextures failed player=" + player.getNameForScoreboard(), throwable);
            UuidRestorerMod.LOGGER.warn("Failed to refresh stored textures for {}", player.getNameForScoreboard(), throwable);
        }
    }

    private static Optional<PlayerBinding> resolveBinding(ServerPlayerEntity player) {
        GameProfile currentProfile = player.getGameProfile();
        UuidRestorerTrace.log(
            "profile-service",
            "resolveBinding player=" + player.getNameForScoreboard()
                + " currentProfile=" + UuidRestorerTrace.describeGameProfile(currentProfile)
                + " playerUuid=" + player.getUuid()
        );
        Optional<PlayerBinding> binding = UuidRestorerMod.controller().getBinding(AuthlibCompat.readName(currentProfile));
        if (binding.isEmpty()) {
            UuidRestorerTrace.log("profile-service", "resolveBinding result=empty reason=noStoredBinding name=" + AuthlibCompat.readName(currentProfile));
            return Optional.empty();
        }

        PlayerBinding playerBinding = binding.get();
        UuidRestorerTrace.binding("profile-service", "resolveBinding.binding", playerBinding);
        if (!playerBinding.hasOnlineProfile()
            || !playerBinding.hasTextures()
            || !Objects.equals(playerBinding.onlineUuid, player.getUuid())) {
            String reason;
            if (!playerBinding.hasOnlineProfile()) {
                reason = "noOnlineProfile";
            } else if (!playerBinding.hasTextures()) {
                reason = "noTextures";
            } else {
                reason = "uuidMismatch expected=" + playerBinding.onlineUuid + " actual=" + player.getUuid();
            }
            UuidRestorerTrace.log("profile-service", "resolveBinding result=empty reason=" + reason);
            return Optional.empty();
        }
        UuidRestorerTrace.log("profile-service", "resolveBinding result=match");
        return Optional.of(playerBinding);
    }

    private static boolean matchesBinding(GameProfile currentProfile, PlayerBinding playerBinding) {
        ResolvedProfile current = AuthlibCompat.toResolvedProfile(currentProfile);
        return Objects.equals(current.texturesValue(), playerBinding.texturesValue)
            && Objects.equals(current.texturesSignature(), playerBinding.texturesSignature)
            && Objects.equals(current.name(), playerBinding.canonicalName);
    }

    private static void refreshTrackedEntity(PlayerManager playerManager, ServerWorld world, ServerPlayerEntity player) {
        UuidRestorerTrace.log(
            "profile-service",
            "refreshTrackedEntity player=" + player.getNameForScoreboard()
                + " playerUuid=" + player.getUuid()
                + " observers=" + playerManager.getPlayerList().size()
        );
        ServerChunkLoadingManager chunkLoadingManager =
            ((ServerChunkManagerAccessor) world.getChunkManager()).uuidrestorer$getChunkLoadingManager();
        Object trackedSelf = ((ServerChunkLoadingManagerAccessor) chunkLoadingManager)
            .uuidrestorer$getEntityTrackers()
            .get(player.getId());

        if (trackedSelf instanceof EntityTrackerAccessor selfTracker) {
            for (ServerPlayerEntity observer : playerManager.getPlayerList()) {
                selfTracker.uuidrestorer$stopTracking(observer);
                selfTracker.uuidrestorer$updateTrackedStatus(observer);
            }
        }

        for (ServerPlayerEntity observer : playerManager.getPlayerList()) {
            Object trackedObserver = ((ServerChunkLoadingManagerAccessor) chunkLoadingManager)
                .uuidrestorer$getEntityTrackers()
                .get(observer.getId());
            if (trackedObserver instanceof EntityTrackerAccessor observerTracker) {
                observerTracker.uuidrestorer$stopTracking(player);
                observerTracker.uuidrestorer$updateTrackedStatus(player);
            }
        }
    }
}
