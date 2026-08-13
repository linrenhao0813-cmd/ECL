package com.ecl.game;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Maven coordinates ({@code group:artifact:version}) into repository paths and URLs.
 *
 * <p>Fabric/Quilt profile JSONs from the Fabric meta service declare their dependencies as
 * bare coordinates — each library entry carries only a {@code name} and a repository {@code url},
 * without the {@code downloads.artifact} block that Mojang-style version JSONs use. Launchers are
 * expected to resolve the jar through the Maven layout. This class centralizes that resolution so
 * ECL can download such libraries and put them on the launch classpath.
 */
public final class MavenCoordinates {

    /**
     * A simple three-part coordinate: group:artifact:version (no classifier).
     *
     * <p>Every segment must start with an alphanumeric character and may contain only
     * alphanumerics, {@code .}, {@code _}, {@code +} and {@code -}. This deliberately rejects
     * path separators ({@code /}, {@code \}) and segments like {@code .} / {@code ..}, so the
     * resolved repository path can never escape the libraries directory via path traversal.
     */
    private static final Pattern COORDINATE =
            Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._+\\-]*):"
                    + "([A-Za-z0-9][A-Za-z0-9._+\\-]*):"
                    + "([A-Za-z0-9][A-Za-z0-9._+\\-]*)$");

    private MavenCoordinates() {
    }

    /** True when {@code name} is a plain three-part Maven coordinate without a classifier. */
    public static boolean isSimpleCoordinate(String name) {
        return name != null && COORDINATE.matcher(name).matches();
    }

    /**
     * Maps {@code group:artifact:version} to the jar path under the repository root,
     * e.g. {@code net.fabricmc:fabric-loader:0.19.3} to
     * {@code net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar}.
     */
    public static String repositoryPath(String name) {
        Matcher matcher = COORDINATE.matcher(name == null ? "" : name);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a simple Maven coordinate: " + name);
        }
        String group = matcher.group(1);
        String artifact = matcher.group(2);
        String version = matcher.group(3);
        return group.replace('.', '/') + '/' + artifact + '/' + version
                + '/' + artifact + '-' + version + ".jar";
    }

    /**
     * Joins a repository root (with or without a trailing slash) with the coordinate path,
     * e.g. {@code https://maven.fabricmc.net/} + {@code net/fabricmc/...} into a full URL.
     */
    public static String repositoryUrl(String repository, String name) {
        String base = repository == null || repository.isBlank() ? "" : repository;
        if (!base.isEmpty() && !base.endsWith("/")) {
            base = base + "/";
        }
        return base + repositoryPath(name);
    }
}
