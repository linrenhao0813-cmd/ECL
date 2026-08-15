package com.ecl.auth.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineSkinServerTest {

    private static final String UUID_HEX = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path directory;

    private OfflineSkinServer server;
    private String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Path skin = directory.resolve("skin.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        server = new OfflineSkinServer();
        baseUrl = server.baseUrl();
        server.registerCharacter(UUID_HEX, "Steve", skin, true);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesMetadataWithSignaturePublicKey() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue(root.get("signaturePublickey").getAsString().contains("BEGIN PUBLIC KEY"));
        JsonArray domains = root.getAsJsonArray("skinDomains");
        assertTrue(domains.contains(new JsonPrimitive("127.0.0.1")));
        assertTrue(root.getAsJsonObject("meta").get("implementationName").getAsString().length() > 0);
    }

    @Test
    void servesProfileWithSignedTexturesProperty() throws Exception {
        HttpResponse<String> response = get("/sessionserver/session/minecraft/profile/" + UUID_HEX);
        assertEquals(200, response.statusCode());
        JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals(UUID_HEX, profile.get("id").getAsString());
        assertEquals("Steve", profile.get("name").getAsString());

        JsonObject property = profile.getAsJsonArray("properties").get(0).getAsJsonObject();
        assertEquals("textures", property.get("name").getAsString());
        assertTrue(property.has("signature"), "textures property must be signed");

        String payloadJson = new String(Base64.getDecoder().decode(property.get("value").getAsString()),
                StandardCharsets.UTF_8);
        JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
        assertEquals("Steve", payload.get("profileName").getAsString());
        JsonObject skin = payload.getAsJsonObject("textures").getAsJsonObject("SKIN");
        assertTrue(skin.get("url").getAsString().startsWith(baseUrl + "/textures/"),
                "texture url points at the local server");
        assertEquals("slim", skin.getAsJsonObject("metadata").get("model").getAsString());
    }

    @Test
    void servesTexturePngBytes() throws Exception {
        HttpResponse<String> profile = get("/sessionserver/session/minecraft/profile/" + UUID_HEX);
        JsonObject property = JsonParser.parseString(profile.body())
                .getAsJsonObject().getAsJsonArray("properties").get(0).getAsJsonObject();
        String payloadJson = new String(Base64.getDecoder().decode(property.get("value").getAsString()),
                StandardCharsets.UTF_8);
        String textureUrl = JsonParser.parseString(payloadJson)
                .getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN")
                .get("url").getAsString();

        HttpResponse<byte[]> png = client.send(HttpRequest.newBuilder(URI.create(textureUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, png.statusCode());
        assertEquals("image/png", png.headers().firstValue("Content-Type").orElse(""));
        byte[] body = png.body();
        assertEquals(0x89, body[0] & 0xFF);
        assertEquals('P', body[1]);
        assertEquals('N', body[2]);
        assertEquals('G', body[3]);
    }

    @Test
    void resolvesNamesViaProfilesEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/profiles/minecraft"))
                .POST(HttpRequest.BodyPublishers.ofString("[\"Steve\",\"Ghost\"]"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonArray profiles = JsonParser.parseString(response.body()).getAsJsonArray();
        assertEquals(1, profiles.size());
        assertEquals("Steve", profiles.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void missingProfileReturnsNoContent() throws Exception {
        HttpResponse<String> response = get("/sessionserver/session/minecraft/profile/ffffffffffffffffffffffffffffffff");
        assertEquals(204, response.statusCode());
    }

    @Test
    void joinEndpointIsAccepted() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/sessionserver/session/minecraft/join"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());
    }

    @Test
    void sharedServerStopsAfterLastLeaseCloses() throws Exception {
        OfflineSkinServer.Lease first = OfflineSkinServer.acquire();
        OfflineSkinServer.Lease second = OfflineSkinServer.acquire();
        String sharedBaseUrl = first.server().baseUrl();
        try {
            assertEquals(sharedBaseUrl, second.server().baseUrl());
            first.close();
            HttpResponse<String> stillRunning = client.send(
                    HttpRequest.newBuilder(URI.create(sharedBaseUrl + "/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stillRunning.statusCode());

            second.close();
            assertThrows(IOException.class, () -> client.send(
                    HttpRequest.newBuilder(URI.create(sharedBaseUrl + "/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void decodesEncodedUsernameQueryParameter() throws Exception {
        Path skin = directory.resolve("space-name.png");
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", skin.toFile());
        OfflineSkinServer.Registration registration = server.registerCharacter(
                "fedcba9876543210fedcba9876543210", "Space Name", skin, false);
        try {
            HttpResponse<String> response = get(
                    "/sessionserver/session/minecraft/hasJoined?serverId=test&username=Space+Name");
            assertEquals(200, response.statusCode());
            assertEquals("Space Name", JsonParser.parseString(response.body())
                    .getAsJsonObject().get("name").getAsString());

            assertNull(OfflineSkinServer.queryParameter("username=%ZZ", "username"));
        } finally {
            registration.close();
        }
    }

    @Test
    void repeatedRegistrationsNeverEvictTheActiveTexture() throws Exception {
        List<OfflineSkinServer.Registration> registrations = new ArrayList<>();
        try {
            for (int index = 0; index < 24; index++) {
                Path skin = directory.resolve("skin-" + index + ".png");
                BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, 0xFF000000 | index);
                ImageIO.write(image, "png", skin.toFile());
                registrations.add(server.registerCharacter(UUID_HEX, "Steve", skin, true));
            }

            HttpResponse<String> profile = get("/sessionserver/session/minecraft/profile/" + UUID_HEX);
            JsonObject property = JsonParser.parseString(profile.body())
                    .getAsJsonObject().getAsJsonArray("properties").get(0).getAsJsonObject();
            String payload = new String(Base64.getDecoder().decode(property.get("value").getAsString()),
                    StandardCharsets.UTF_8);
            String textureUrl = JsonParser.parseString(payload).getAsJsonObject()
                    .getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            HttpResponse<byte[]> texture = client.send(
                    HttpRequest.newBuilder(URI.create(textureUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, texture.statusCode());
            assertTrue(texture.body().length > 8);
        } finally {
            registrations.forEach(OfflineSkinServer.Registration::close);
        }
    }

    @Test
    void malformedProfileBatchReturnsEmptyList() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/profiles/minecraft"))
                .POST(HttpRequest.BodyPublishers.ofString("not-json"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(0, JsonParser.parseString(response.body()).getAsJsonArray().size());
    }
}
