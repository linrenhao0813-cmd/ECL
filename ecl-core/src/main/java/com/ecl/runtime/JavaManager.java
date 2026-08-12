package com.ecl.runtime;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface JavaManager {
    List<JavaRuntimeInfo> detect();

    Optional<JavaRuntimeInfo> select(int requiredFeatureVersion);

    JavaRuntimeInfo requireOrInstall(int requiredFeatureVersion, Consumer<String> status,
                                     BiConsumer<Long, Long> progress) throws IOException;
}
