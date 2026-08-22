package com.ecl.modrinth.pack;

import com.ecl.launcher.ModLoaderInstaller;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;

/** Validates MRPACK dependencies and selects at most one declared Loader. */
final class MrpackDependencyResolver {
    private MrpackDependencyResolver() {
    }

    static JsonObject requireDependencies(JsonObject index, String missingMessage)
            throws IOException {
        if (!index.has("dependencies") || !index.get("dependencies").isJsonObject()) {
            throw new IOException(missingMessage);
        }
        return index.getAsJsonObject("dependencies");
    }

    static String requireMinecraftVersion(JsonObject dependencies, String missingMessage)
            throws IOException {
        String minecraftVersion = JsonUtil.getString(dependencies, "minecraft", "");
        if (minecraftVersion.isBlank()) {
            throw new IOException(missingMessage);
        }
        return minecraftVersion;
    }

    static LoaderDependency findLoader(JsonObject dependencies) throws IOException {
        LoaderDependency found = null;
        for (var candidate : new Object[][]{
                {"fabric-loader", ModLoaderInstaller.Loader.FABRIC},
                {"quilt-loader", ModLoaderInstaller.Loader.QUILT},
                {"forge", ModLoaderInstaller.Loader.FORGE},
                {"neoforge", ModLoaderInstaller.Loader.NEOFORGE}}) {
            String key = (String) candidate[0];
            if (!dependencies.has(key)) {
                continue;
            }
            if (found != null) {
                throw new IOException("整合包同时声明了多个模组加载器");
            }
            JsonElement value = dependencies.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw new IOException("整合包声明了无效的加载器版本: " + key);
            }
            found = new LoaderDependency((ModLoaderInstaller.Loader) candidate[1],
                    value.getAsString());
        }
        return found;
    }
}

record LoaderDependency(ModLoaderInstaller.Loader loader, String version) {
}
