package com.ecl.launcher;

import java.io.File;
import java.io.IOException;

import com.ecl.auth.AuthProvider;

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

    Process launch() throws IOException;
}
