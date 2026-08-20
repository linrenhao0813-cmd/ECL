package com.ecl.modrinth.pack;

import com.ecl.modrinth.model.ModFile;
import com.ecl.modrinth.model.ModVersion;

/** A newer compatible pack version and its verified download file. */
public record ModpackUpdate(
        ModpackInstance instance,
        ModVersion availableVersion,
        ModFile selectedFile
) {
}
