package com.ecl.pack;

import java.nio.file.Path;

public record PackImportResult(PackFormat format, String instanceName, Path instanceDirectory,
                               int extractedFiles) {
}
