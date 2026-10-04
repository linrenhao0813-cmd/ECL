package com.ecl.util;

/** Scoped test access to loopback HTTP artifact servers; production policy remains strict. */
public final class TestNetworkPolicy {
    private TestNetworkPolicy() {
    }

    public static AutoCloseable allowLoopbackArtifactDownloads() {
        return NetworkUriPolicy.allowLoopbackArtifactDownloadsForTesting();
    }
}
