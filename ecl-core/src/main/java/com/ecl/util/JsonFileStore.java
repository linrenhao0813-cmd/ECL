package com.ecl.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** UTF-8 JSON file persistence kept separate from HTTP transport. */
final class JsonFileStore {
    private JsonFileStore() {
    }

    static void write(File file, JsonObject object) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(GsonProvider.pretty().toJson(object));
        }
    }

    static JsonObject read(File file) throws IOException {
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }
}
