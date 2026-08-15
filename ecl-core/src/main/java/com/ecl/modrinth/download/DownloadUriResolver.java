package com.ecl.modrinth.download;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
public interface DownloadUriResolver {
    URI resolve(URI uri) throws IOException;
}
