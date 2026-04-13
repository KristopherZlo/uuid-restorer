package dev.creas.uuidrestorer.runtime;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public interface ServerAccess {
    boolean isOnlineMode();

    boolean isDedicatedServer();

    Optional<ResolvedProfile> resolveProfileByName(String name);

    Optional<ResolvedProfile> resolveProfileById(UUID id);

    Optional<ResolvedProfile> fetchTextures(ResolvedProfile profile);

    Path rootPath();

    Path playerdataDirectory();

    Path statsDirectory();

    Path advancementsDirectory();
}
