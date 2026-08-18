package com.ecl.launcher;

import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.ecl.auth.AuthProvider;
import com.ecl.auth.OfflineSkin;

/**
 * Service interface for launching the Minecraft game process.
 */
public interface LaunchService {

    void setAuth(AuthProvider auth);

    void setVersion(String versionId);

    void setMaxMemory(int mb);

    void setMinMemory(int mb);

    void setGameDir(File dir);

    /** Immutable instance root used for private libraries and native extraction. */
    default void setInstanceDir(File dir) {
    }

    void setJvmArgs(String args);

    void setJavaPath(String javaPath);

    void setGameResolution(int width, int height);

    void setFullscreen(boolean fullscreen);

    void setServerAddress(String serverAddress);

    void setProcessorCount(int processorCount);

    /** Locally imported skin to inject for an offline account; no-op by default. */
    default void setOfflineSkin(OfflineSkin skin) {
    }

    /** Whether preparing the selected version requires a managed Java download. */
    default boolean requiresJavaRuntimeDownload() throws IOException {
        return false;
    }

    /** Resolve or download the Java runtime before launching so UI task centers can observe it. */
    default String prepareJavaRuntime(Consumer<String> status,
                                      BiConsumer<Long, Long> progress) throws IOException {
        return null;
    }

    Process launch() throws IOException;
}
