package com.ecl.runtime;

import java.nio.file.Path;

public record JavaRuntimeInfo(Path executable, int featureVersion, String architecture,
                              String vendor, boolean jdk, boolean managed) {
}
