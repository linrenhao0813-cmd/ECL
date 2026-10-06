package com.ecl.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** UTF-8 JSON file persistence kept separate from HTTP transport. */
final class JsonFileStore {
    private JsonFileStore() {
    }

    static void write(File file, JsonObject object) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Path target = file.toPath().toAbsolutePath();
        Path directory = target.getParent();
        Path temp = Files.createTempFile(directory, ".ecl-json-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(GsonProvider.pretty().toJson(object));
            }
            FileUtil.moveReplacing(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static JsonObject read(File file) throws IOException {
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }
}
