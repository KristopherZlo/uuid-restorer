package dev.creas.uuidrestorer.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangProfileResolverTest {
    private static final UUID ALICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");

    private HttpServer server;
    private MojangProfileResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/users/profiles/minecraft/Alice", exchange -> respond(exchange, 200, "{\"id\":\"123456781234123412341234567890ab\",\"name\":\"Alice\"}"));
        server.createContext("/users/profiles/minecraft/Missing", exchange -> respond(exchange, 404, ""));
        server.createContext("/users/profiles/minecraft/Bob", exchange -> respond(exchange, 200, "{\"id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"name\":\"Bob\"}"));
        server.createContext("/users/profiles/minecraft/Carol", exchange -> respond(exchange, 200, "{\"id\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"name\":\"Carol\"}"));
        server.createContext("/session/minecraft/profile/123456781234123412341234567890ab", exchange -> respond(exchange, 200, "{\"id\":\"123456781234123412341234567890ab\",\"name\":\"Alice\",\"properties\":[{\"name\":\"textures\",\"value\":\"value\",\"signature\":\"sig\"}]}"));
        server.createContext("/session/minecraft/profile/ffffffffffffffffffffffffffffffff", exchange -> respond(exchange, 404, ""));
        server.createContext("/session/minecraft/profile/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", exchange -> respond(exchange, 404, ""));
        server.createContext("/session/minecraft/profile/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", exchange -> respond(exchange, 500, "{\"error\":\"server\"}"));
        server.start();

        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        resolver = new MojangProfileResolver(
            HttpClient.newHttpClient(),
            baseUri.resolve("/users/profiles/minecraft/"),
            baseUri.resolve("/session/minecraft/profile/")
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolveProfileByNameHydratesTextures() {
        ResolvedProfile profile = resolver.resolveProfileByName("Alice").orElseThrow();

        assertEquals(ALICE_UUID, profile.uuid());
        assertEquals("Alice", profile.name());
        assertEquals("value", profile.texturesValue());
        assertEquals("sig", profile.texturesSignature());
    }

    @Test
    void resolveProfileByNameReturnsEmptyWhenNicknameDoesNotExist() {
        Optional<ResolvedProfile> missing = resolver.resolveProfileByName("Missing");

        assertTrue(missing.isEmpty());
    }

    @Test
    void resolveProfileByNameFallsBackToBasicProfileWhenSessionProfileIsMissing() {
        ResolvedProfile profile = resolver.resolveProfileByName("Bob").orElseThrow();

        assertEquals(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), profile.uuid());
        assertEquals("Bob", profile.name());
        assertTrue(!profile.hasTextures());
    }

    @Test
    void resolveProfileByNameFallsBackToBasicProfileWhenSessionProfileErrors() {
        ResolvedProfile profile = resolver.resolveProfileByName("Carol").orElseThrow();

        assertEquals(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), profile.uuid());
        assertEquals("Carol", profile.name());
        assertTrue(!profile.hasTextures());
    }

    @Test
    void resolveProfileByIdReturnsEmptyWhenSessionProfileDoesNotExist() {
        Optional<ResolvedProfile> missing = resolver.resolveProfileById(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        assertTrue(missing.isEmpty());
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
