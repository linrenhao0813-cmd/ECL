package com.ecl.launch;

import com.ecl.auth.AuthProvider;
import com.ecl.game.VersionMetadata;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds and applies the {@code ${...}} token map Minecraft's launch arguments use. Kept distinct
 * from the command builder so the substitution rules stay easy to spot and unit test.
 */
final class LaunchVariables {

    private LaunchVariables() {
    }

    /** Compute the token map for {@code version}. */
    static Map<String, String> of(LaunchOptions options, VersionMetadata version) {
        File gameDir = options.gameDirectory();
        File assetsRoot = options.environment().assetsDirectory();
        File librariesDir = options.environment().librariesDirectory();
        AuthProvider auth = options.auth();

        Map<String, String> variables = new HashMap<>();
        variables.put("${auth_player_name}", auth.getUsername());
        variables.put("${auth_session}", auth.getAccessToken());
        variables.put("${auth_uuid}", auth.getUUID());
        variables.put("${auth_access_token}", auth.getAccessToken());
        variables.put("${version_name}", options.versionId());
        variables.put("${version_type}", options.environment().launcherName());
        variables.put("${game_directory}", gameDir == null ? "" : gameDir.getAbsolutePath());
        variables.put("${assets_root}", assetsRoot.getAbsolutePath());
        variables.put("${assets_index_name}", assetIndexName(version));
        variables.put("${user_type}", auth.getType().name().toLowerCase());
        variables.put("${natives_directory}", options.environment().nativesDirectory(options.versionId()).getAbsolutePath());
        variables.put("${library_directory}", librariesDir.getAbsolutePath());
        variables.put("${classpath_separator}", File.pathSeparator);
        variables.put("${launcher_name}", options.environment().launcherName());
        variables.put("${launcher_version}", options.environment().launcherVersion());
        return variables;
    }

    private static String assetIndexName(VersionMetadata version) {
        if (version != null && version.assetIndex() != null && version.assetIndex().hasId()) {
            return version.assetIndex().id();
        }
        return version == null ? "" : version.id();
    }

    /** Replace every known token in {@code argument}. Values are substituted verbatim. */
    static String substitute(String argument, Map<String, String> variables) {
        if (argument == null || argument.indexOf("${") < 0) {
            return argument;
        }
        String result = argument;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getValue() != null && result.indexOf(entry.getKey()) >= 0) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
