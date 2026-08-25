package com.ecl.modrinth.service;

/**
 * Bounds applied when resolving a mod dependency graph.
 *
 * <p>The depth limit stops dependency chains from descending indefinitely (a pathological pack or
 * a malicious project could otherwise produce a very deep tree). The node limit bounds the total
 * number of resolved projects in one install so a single action cannot fan out into thousands of
 * downloads. Both limits are generous for real Minecraft modpacks but keep the resolver and the
 * downloader safe against runaway graphs.</p>
 */
public final class DependencyResolverLimits {
    /** Maximum inheritance depth of the dependency tree (root counts as depth 0). */
    public static final int MAX_DEPTH = 32;

    /** Maximum number of distinct projects resolved for a single install. */
    public static final int MAX_DEPENDENCIES = 256;

    private DependencyResolverLimits() {
    }
}
