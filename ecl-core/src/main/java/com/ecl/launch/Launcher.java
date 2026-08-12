package com.ecl.launch;

/**
 * Launches a Minecraft instance from validated {@link LaunchOptions}.
 *
 * <p>Implementations own the ordering that matters for a reliable start: resolve and validate the
 * version, pick a compatible Java runtime, stage native libraries, assemble the command, start the
 * process, and hand back a {@link GameProcess}. This is the façade the rest of the launcher talks to;
 * a different implementation may substitute a dry-run, headless, or debugable launch.</p>
 */
public interface Launcher {

    /** Resolve and validate a launch without creating a process. */
    LaunchCommand prepare(LaunchOptions options) throws LaunchException;

    /**
     * Start the game process.
     *
     * @param options fully populated launch settings
     * @return control handle for the running process
     * @throws LaunchException when the launch cannot be prepared or started
     */
    GameProcess launch(LaunchOptions options) throws LaunchException;
}
