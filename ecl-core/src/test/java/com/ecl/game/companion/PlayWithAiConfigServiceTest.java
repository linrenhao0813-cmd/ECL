package com.ecl.game.companion;

import com.ecl.game.DefaultGameRepository;
import com.ecl.game.DefaultIsolationType;
import com.ecl.launch.GameProcessMarker;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayWithAiConfigServiceTest {
    @Test
    void readsDefaultsAndWritesConfiguredFieldsWithoutDroppingUnknownFields(@TempDir Path root)
            throws Exception {
        Path versions = root.resolve("versions");
        Path shared = root.resolve("shared");
        Path metadata = versions.resolve("fabric");
        Path instance = shared.resolve("versions/fabric");
        Files.createDirectories(metadata);
        Files.createDirectories(instance);
        Files.writeString(metadata.resolve("fabric.json"), "{\"id\":\"fabric\",\"eclMinecraftVersion\":\"1.21.4\",\"eclModLoader\":\"fabric\"}");
        Path config = instance.resolve("config/minecraft-ai-companion.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                {
                  "schemaVersion": 0,
                  "aiProvider": "OPENAI",
                  "apiKey": "old-key",
                  "baseUrl": "https://old.example/v1",
                  "model": "old-model",
                  "oauthProvider": "GOOGLE",
                  "oauthClientId": "oauth-client",
                  "oauthUser": "player@example.invalid",
                  "futureField": {"keep": true}
                }
                """);

        DefaultGameRepository repository = new DefaultGameRepository(versions, shared,
                DefaultIsolationType.ALWAYS);
        PlayWithAiConfigService service = new PlayWithAiConfigService();

        PlayWithAiConfigService.Config loaded = service.load(repository, "fabric");
        assertEquals("old-key", loaded.apiKey());
        assertTrue(loaded.hasApiKey());
        assertEquals("https://old.example/v1", loaded.baseUrl());

        service.save(repository, "fabric", PlayWithAiConfigService.AiProvider.GROQ,
                "new-key", "https://api.example/v1", "example-model");

        var json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertEquals("GROQ", json.get("aiProvider").getAsString());
        assertEquals("oauth-client", json.get("oauthClientId").getAsString());
        assertTrue(json.getAsJsonObject("futureField").get("keep").getAsBoolean());
        assertEquals("new-key", json.get("apiKey").getAsString());
    }

    @Test
    void validatesHttpAndHttpsOnly(@TempDir Path root) throws Exception {
        assertThrows(java.io.IOException.class,
                () -> PlayWithAiConfigService.validateBaseUrl("file:///tmp/config"));
        assertThrows(java.io.IOException.class,
                () -> PlayWithAiConfigService.validateBaseUrl("https://user:secret@example.com/api"));
        PlayWithAiConfigService.validateBaseUrl("http://localhost:11434/v1");
        PlayWithAiConfigService.validateBaseUrl("https://api.example/v1");
    }

    @Test
    void doesNotOverwriteMalformedConfigOrWriteWhileRunning(@TempDir Path root) throws Exception {
        Path versions = root.resolve("versions");
        Path shared = root.resolve("shared");
        Path metadata = versions.resolve("fabric");
        Path instance = shared.resolve("versions/fabric");
        Files.createDirectories(metadata);
        Files.createDirectories(instance);
        Files.writeString(metadata.resolve("fabric.json"), "{\"id\":\"fabric\",\"eclMinecraftVersion\":\"1.21.4\"}");
        Path config = instance.resolve("config/minecraft-ai-companion.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{not json");
        DefaultGameRepository repository = new DefaultGameRepository(versions, shared,
                DefaultIsolationType.ALWAYS);

        PlayWithAiConfigService service = new PlayWithAiConfigService(ignored -> false);
        assertThrows(java.io.IOException.class, () -> service.load(repository, "fabric"));
        assertThrows(java.io.IOException.class, () -> service.save(repository, "fabric",
                PlayWithAiConfigService.AiProvider.OPENAI, "secret", "https://api.example", "model"));
        assertEquals("{not json", Files.readString(config));
        assertFalse(Files.exists(config.resolveSibling("minecraft-ai-companion.json.tmp")));

        PlayWithAiConfigService running = new PlayWithAiConfigService("fabric"::equals);
        assertThrows(java.io.IOException.class, () -> running.save(repository, "fabric",
                PlayWithAiConfigService.AiProvider.OPENAI, "secret", "https://api.example", "model"));
    }

    @Test
    void loadsInvalidEndpointForRepairAndRejectsItOnlyWhenSaving(@TempDir Path root)
            throws Exception {
        TestInstance instance = createInstance(root);
        Files.writeString(instance.config(), """
                {"schemaVersion":1,"aiProvider":"CUSTOM","apiKey":"key",
                 "baseUrl":"file:///tmp/api","model":"model"}
                """);
        PlayWithAiConfigService service = new PlayWithAiConfigService();

        PlayWithAiConfigService.Config loaded = service.load(instance.repository(), "fabric");
        assertEquals("file:///tmp/api", loaded.baseUrl());
        assertThrows(java.io.IOException.class, () -> service.save(instance.repository(), "fabric",
                PlayWithAiConfigService.AiProvider.CUSTOM, "key", loaded.baseUrl(), "model"));

        service.save(instance.repository(), "fabric", PlayWithAiConfigService.AiProvider.CUSTOM,
                "key", "http://localhost:11434/v1", "model");
        assertEquals("http://localhost:11434/v1", JsonParser.parseString(
                Files.readString(instance.config())).getAsJsonObject().get("baseUrl").getAsString());
    }

    @Test
    void refusesToDowngradeNewerSchemas(@TempDir Path root) throws Exception {
        TestInstance instance = createInstance(root);
        String future = """
                {"schemaVersion":2,"aiProvider":"OPENAI","apiKey":"future-key",
                 "baseUrl":"https://api.example/v2","model":"future-model",
                 "futureField":{"keep":true}}
                """;
        Files.writeString(instance.config(), future);
        PlayWithAiConfigService service = new PlayWithAiConfigService();

        assertThrows(java.io.IOException.class,
                () -> service.load(instance.repository(), "fabric"));
        assertThrows(java.io.IOException.class, () -> service.save(instance.repository(), "fabric",
                PlayWithAiConfigService.AiProvider.OPENAI, "replacement",
                "https://api.example/v1", "model"));
        assertEquals(future, Files.readString(instance.config()));
    }

    @Test
    void detectsGamesThatOutliveTheLauncherProcessState(@TempDir Path root) throws Exception {
        TestInstance instance = createInstance(root);
        PlayWithAiConfigService service = new PlayWithAiConfigService(ignored -> false);
        ProcessHandle current = ProcessHandle.current();
        GameProcessMarker.record(instance.instanceDirectory(), current);
        try {
            PlayWithAiConfigService.Config loaded = service.load(instance.repository(), "fabric");
            assertTrue(loaded.instanceRunning());
            assertThrows(java.io.IOException.class, () -> service.save(instance.repository(), "fabric",
                    PlayWithAiConfigService.AiProvider.OPENAI, "key",
                    "https://api.example/v1", "model"));
        } finally {
            GameProcessMarker.clear(instance.instanceDirectory(), current);
        }
    }

    private static TestInstance createInstance(Path root) throws Exception {
        Path versions = root.resolve("versions");
        Path shared = root.resolve("shared");
        Path metadata = versions.resolve("fabric");
        Path instance = shared.resolve("versions/fabric");
        Files.createDirectories(metadata);
        Files.createDirectories(instance.resolve("config"));
        Files.writeString(metadata.resolve("fabric.json"),
                "{\"id\":\"fabric\",\"eclMinecraftVersion\":\"1.21.4\","
                        + "\"eclModLoader\":\"fabric\"}");
        DefaultGameRepository repository = new DefaultGameRepository(versions, shared,
                DefaultIsolationType.ALWAYS);
        return new TestInstance(repository, instance,
                instance.resolve("config/minecraft-ai-companion.json"));
    }

    private record TestInstance(DefaultGameRepository repository, Path instanceDirectory,
                                Path config) {
    }
}
