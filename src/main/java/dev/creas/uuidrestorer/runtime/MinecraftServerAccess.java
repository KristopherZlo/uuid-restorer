package dev.creas.uuidrestorer.runtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public final class MinecraftServerAccess implements ServerAccess {
    private static final MojangProfileResolver MOJANG_PROFILE_RESOLVER = new MojangProfileResolver();

    private final MinecraftServer server;

    public MinecraftServerAccess(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isOnlineMode() {
        return server.isOnlineMode();
    }

    @Override
    public Optional<ResolvedProfile> resolveProfileByName(String name) {
        return MOJANG_PROFILE_RESOLVER.resolveProfileByName(name);
    }

    @Override
    public Optional<ResolvedProfile> resolveProfileById(UUID id) {
        return MOJANG_PROFILE_RESOLVER.resolveProfileById(id);
    }

    @Override
    public Optional<ResolvedProfile> fetchTextures(ResolvedProfile profile) {
        return MOJANG_PROFILE_RESOLVER.fetchTextures(profile);
    }

    @Override
    public Path rootPath() {
        return server.getSavePath(WorldSavePath.ROOT);
    }

    @Override
    public Path playerdataDirectory() {
        return server.getSavePath(WorldSavePath.PLAYERDATA);
    }

    @Override
    public Path statsDirectory() {
        return server.getSavePath(WorldSavePath.STATS);
    }

    @Override
    public Path advancementsDirectory() {
        return server.getSavePath(WorldSavePath.ADVANCEMENTS);
    }
}
