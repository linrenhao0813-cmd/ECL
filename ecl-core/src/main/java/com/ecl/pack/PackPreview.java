package com.ecl.pack;

import java.util.List;

public record PackPreview(PackFormat format, String name, String minecraftVersion,
                          int fileCount, long archiveBytes, List<String> warnings) {
    public PackPreview {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
