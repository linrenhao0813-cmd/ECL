package com.ecl.download;

import com.ecl.game.AssetIndex;
import com.ecl.util.FileUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyAssetMaterializerTest {
    @TempDir Path temp;

    @Test
    void mapsIndexContentsToVirtualAndActualRunDirectories() throws Exception {
        Path assets = temp.resolve("assets");
        Path game = temp.resolve("custom-instance");
        AssetIndex index = writeIndex(assets, "sounds/test.ogg", "sound", "\"virtual\":true,\"map_to_resources\":true,");

        assertEquals(assets.resolve("virtual/legacy").toFile(),
                LegacyAssetMaterializer.gameAssetsDirectory(index, assets.toFile(), game.toFile()));
        assertFalse(Files.exists(assets.resolve("virtual")), "argument preview must remain read-only");
        LegacyAssetMaterializer.prepare(index, assets.toFile(), game.toFile());

        assertEquals("sound", Files.readString(assets.resolve("virtual/legacy/sounds/test.ogg")));
        assertEquals("sound", Files.readString(game.resolve("resources/sounds/test.ogg")));
    }

    @Test
    void repairedObjectReplacesAnEquallySizedCorruptView() throws Exception {
        Path assets = temp.resolve("assets");
        AssetIndex index = writeIndex(assets, "sound.ogg", "good", "\"virtual\":true,");
        Path game = temp.resolve("game");
        LegacyAssetMaterializer.prepare(index, assets.toFile(), game.toFile());
        Path view = assets.resolve("virtual/legacy/sound.ogg");
        Files.writeString(view, "bad!");
        String hash = FileUtil.sha1("good".getBytes(StandardCharsets.UTF_8));
        Path object = assets.resolve("objects/" + hash.substring(0, 2) + "/" + hash);
        Path replacement = Files.writeString(object.resolveSibling("repaired.tmp"), "good");
        FileUtil.moveReplacing(replacement, object);

        LegacyAssetMaterializer.prepare(index, assets.toFile(), game.toFile());

        assertEquals("good", Files.readString(view));
    }

    @Test
    void resourceArgumentUsesTheActualRunDirectory() throws Exception {
        Path assets = temp.resolve("assets");
        Path game = temp.resolve("custom-instance");
        AssetIndex index = writeIndex(assets, "lang/en.lang", "language", "\"map_to_resources\":true,");

        assertEquals(game.resolve("resources").toFile(),
                LegacyAssetMaterializer.gameAssetsDirectory(index, assets.toFile(), game.toFile()));
        assertFalse(Files.exists(game));
    }

    @Test
    void refusesTamperedIndexesAndEscapingAssetNames() throws Exception {
        Path assets = temp.resolve("assets");
        Path game = temp.resolve("game");
        AssetIndex index = writeIndex(assets, "../../escape", "sound", "\"virtual\":true,");
        assertThrows(IOException.class, () -> LegacyAssetMaterializer.prepare(index, assets.toFile(), game.toFile()));
        assertFalse(Files.exists(assets.resolve("escape")));

        Files.writeString(assets.resolve("indexes/legacy.json"), "{\"virtual\":true,\"objects\":{}}");
        assertThrows(IOException.class, () ->
                LegacyAssetMaterializer.gameAssetsDirectory(index, assets.toFile(), game.toFile()));
    }

    @Test
    void rejectsSameSizedWrongObjectBeforeReplacingExistingViews() throws Exception {
        Path assets = temp.resolve("assets");
        AssetIndex index = writeIndex(assets, "sound.ogg", "good", "\"virtual\":true,\"map_to_resources\":true,");
        String hash = FileUtil.sha1("good".getBytes(StandardCharsets.UTF_8));
        Files.writeString(assets.resolve("objects/" + hash.substring(0, 2) + "/" + hash), "bad!");
        Path virtual = assets.resolve("virtual/legacy/sound.ogg");
        Path resources = temp.resolve("game/resources/sound.ogg");
        Files.createDirectories(virtual.getParent());
        Files.createDirectories(resources.getParent());
        Files.writeString(virtual, "previous-virtual");
        Files.writeString(resources, "previous-resources");
        assertThrows(IOException.class, () -> LegacyAssetMaterializer.prepare(index, assets.toFile(), temp.resolve("game").toFile()));
        assertEquals("previous-virtual", Files.readString(virtual));
        assertEquals("previous-resources", Files.readString(resources));
    }

    private AssetIndex writeIndex(Path assets, String name, String value, String flags) throws IOException {
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        String hash = FileUtil.sha1(payload);
        Path object = assets.resolve("objects/" + hash.substring(0, 2) + "/" + hash);
        Files.createDirectories(object.getParent());
        Files.write(object, payload);
        JsonObject entry = JsonParser.parseString("{\"hash\":\"" + hash + "\",\"size\":" + payload.length + "}").getAsJsonObject();
        JsonObject objects = new JsonObject();
        objects.add(name, entry);
        byte[] bytes = ("{" + flags + "\"objects\":" + objects + "}").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(assets.resolve("indexes"));
        Files.write(assets.resolve("indexes/legacy.json"), bytes);
        return new AssetIndex("legacy", "https://example/index.json", FileUtil.sha1(bytes), payload.length);
    }
}
