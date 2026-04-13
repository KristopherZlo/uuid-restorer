package dev.creas.uuidrestorer.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.creas.uuidrestorer.service.UuidRestorerTrace;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public final class MojangProfileResolver {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final URI nameLookupBaseUri;
    private final URI sessionLookupBaseUri;

    public MojangProfileResolver() {
        this(
            HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build(),
            URI.create("https://api.mojang.com/users/profiles/minecraft/"),
            URI.create("https://sessionserver.mojang.com/session/minecraft/profile/")
        );
    }

    MojangProfileResolver(HttpClient httpClient, URI nameLookupBaseUri, URI sessionLookupBaseUri) {
        this.httpClient = httpClient;
        this.nameLookupBaseUri = nameLookupBaseUri;
        this.sessionLookupBaseUri = sessionLookupBaseUri;
    }

    public Optional<ResolvedProfile> resolveProfileByName(String nickname) {
        UuidRestorerTrace.log("mojang", "resolveProfileByName nickname=" + nickname);
        JsonObject basicProfile = requestJson(nameLookupBaseUri.resolve(encodePathSegment(nickname)));
        if (basicProfile == null) {
            UuidRestorerTrace.log("mojang", "resolveProfileByName nickname=" + nickname + " result=empty");
            return Optional.empty();
        }

        UUID uuid = parseUuid(readRequiredString(basicProfile, "id"));
        String canonicalName = readRequiredString(basicProfile, "name");
        UuidRestorerTrace.log("mojang", "resolveProfileByName nickname=" + nickname + " basicUuid=" + uuid + " canonicalName=" + canonicalName);
        try {
            Optional<ResolvedProfile> hydratedProfile = resolveProfileById(uuid);
            if (hydratedProfile.isPresent()) {
                UuidRestorerTrace.log("mojang", "resolveProfileByName nickname=" + nickname + " hydrated=" + UuidRestorerTrace.describeProfile(hydratedProfile.get()));
                return hydratedProfile;
            }
        } catch (RuntimeException ignored) {
            UuidRestorerTrace.log("mojang", "resolveProfileByName nickname=" + nickname + " hydration failed, falling back to basic profile");
        }

        ResolvedProfile fallback = new ResolvedProfile(uuid, canonicalName, null, null);
        UuidRestorerTrace.log("mojang", "resolveProfileByName nickname=" + nickname + " fallback=" + UuidRestorerTrace.describeProfile(fallback));
        return Optional.of(fallback);
    }

    public Optional<ResolvedProfile> resolveProfileById(UUID id) {
        UuidRestorerTrace.log("mojang", "resolveProfileById uuid=" + id);
        JsonObject profile = requestJson(URI.create(sessionLookupBaseUri + compactUuid(id) + "?unsigned=false"));
        if (profile == null) {
            UuidRestorerTrace.log("mojang", "resolveProfileById uuid=" + id + " result=empty");
            return Optional.empty();
        }

        ResolvedProfile resolved = parseGameProfile(profile);
        UuidRestorerTrace.log("mojang", "resolveProfileById uuid=" + id + " result=" + UuidRestorerTrace.describeProfile(resolved));
        return Optional.of(resolved);
    }

    public Optional<ResolvedProfile> fetchTextures(ResolvedProfile profile) {
        if (profile == null || profile.uuid() == null) {
            UuidRestorerTrace.log("mojang", "fetchTextures skipped profile=" + UuidRestorerTrace.describeProfile(profile));
            return Optional.empty();
        }
        UuidRestorerTrace.log("mojang", "fetchTextures uuid=" + profile.uuid());
        return resolveProfileById(profile.uuid());
    }

    private JsonObject requestJson(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            UuidRestorerTrace.log("mojang", "request uri=" + uri + " status=" + response.statusCode() + " bodyLength=" + (response.body() == null ? 0 : response.body().length()));
            if (response.statusCode() == 204 || response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mojang API responded with HTTP " + response.statusCode() + " for " + uri);
            }
            if (response.body() == null || response.body().isBlank()) {
                return null;
            }

            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("Mojang API returned non-object JSON for " + uri);
            }
            return parsed.getAsJsonObject();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            UuidRestorerTrace.log("mojang", "request interrupted uri=" + uri, exception);
            throw new IllegalStateException("Interrupted while contacting Mojang API", exception);
        } catch (IOException exception) {
            UuidRestorerTrace.log("mojang", "request failed uri=" + uri, exception);
            throw new IllegalStateException("Failed to contact Mojang API", exception);
        }
    }

    private static ResolvedProfile parseGameProfile(JsonObject json) {
        UUID uuid = parseUuid(readRequiredString(json, "id"));
        String name = readRequiredString(json, "name");
        String texturesValue = null;
        String texturesSignature = null;

        JsonArray propertyArray = json.getAsJsonArray("properties");
        if (propertyArray != null) {
            for (JsonElement propertyElement : propertyArray) {
                if (!propertyElement.isJsonObject()) {
                    continue;
                }

                JsonObject propertyJson = propertyElement.getAsJsonObject();
                String propertyName = readOptionalString(propertyJson, "name");
                String propertyValue = readOptionalString(propertyJson, "value");
                if (propertyName == null || propertyValue == null) {
                    continue;
                }

                String signature = readOptionalString(propertyJson, "signature");
                if ("textures".equals(propertyName) && texturesValue == null) {
                    texturesValue = propertyValue;
                    texturesSignature = signature;
                }
            }
        }

        return new ResolvedProfile(uuid, name, texturesValue, texturesSignature);
    }

    private static String readRequiredString(JsonObject json, String key) {
        String value = readOptionalString(json, key);
        if (value == null) {
            throw new IllegalStateException("Missing required Mojang API field '" + key + "'");
        }
        return value;
    }

    private static String readOptionalString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID parseUuid(String compactUuid) {
        if (compactUuid.length() != 32) {
            throw new IllegalStateException("Unexpected UUID format from Mojang API: " + compactUuid);
        }
        return UUID.fromString(
            compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20)
        );
    }

    private static String compactUuid(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
