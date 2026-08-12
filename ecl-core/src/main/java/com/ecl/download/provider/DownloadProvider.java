package com.ecl.download.provider;

import java.net.URI;
import java.util.List;

/** Extensible URL resolver for official, mirror or organization-hosted downloads. */
public interface DownloadProvider {
    String id();

    int priority();

    boolean supports(URI original);

    List<URI> resolve(URI original);

    default int concurrentDownloads() {
        return 6;
    }
}
