package com.ecl.modrinth.download;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Guards Modrinth dependency traversal against circular chains. */
final class ModrinthDependencyGuard {
    private ModrinthDependencyGuard() {
    }

    static void ensureNoDependencyCycle(Deque<String> dependencyPath, String versionId)
            throws IOException {
        if (!dependencyPath.contains(versionId)) {
            return;
        }
        List<String> cycle = new ArrayList<>();
        boolean cycleStarted = false;
        for (String current : dependencyPath) {
            if (current.equals(versionId)) {
                cycleStarted = true;
            }
            if (cycleStarted) {
                cycle.add(current);
            }
        }
        cycle.add(versionId);
        throw new IOException("Detected circular Modrinth dependency chain: " + String.join(" -> ", cycle));
    }
}
