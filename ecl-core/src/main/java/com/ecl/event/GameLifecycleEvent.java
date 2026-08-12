package com.ecl.event;

import java.nio.file.Path;

/**
 * Published when the launched Minecraft process changes lifecycle state.
 *
 * <p>The same instance is reused as the process passes through its phases; a consumer should
 * react to {@link Phase} rather than treating separate publications as separate events.</p>
 */
public final class GameLifecycleEvent extends Event {

    public enum Phase {
        /** The JVM command line has been assembled; the game process is being started. */
        LAUNCHING,
        /** The game process is running. */
        STARTED,
        /** The game process exited on its own. */
        EXITED,
        /** The launcher destroyed an aggressively misbehaving game process. */
        TERMINATED
    }

    private final Phase phase;
    private final String versionId;
    private final int exitCode;
    private final Path instanceDirectory;

    public GameLifecycleEvent(Phase phase, String versionId, int exitCode, Path instanceDirectory) {
        this.phase = phase == null ? Phase.LAUNCHING : phase;
        this.versionId = versionId;
        this.exitCode = exitCode;
        this.instanceDirectory = instanceDirectory;
    }

    public Phase phase() {
        return phase;
    }

    public String versionId() {
        return versionId;
    }

    /** OS exit code; only meaningful for {@code EXITED} and {@code TERMINATED}. */
    public int exitCode() {
        return exitCode;
    }

    /** Working directory of the launched instance, or {@code null} when unknown. */
    public Path instanceDirectory() {
        return instanceDirectory;
    }

    @Override
    public String name() {
        return "Game" + phase.name();
    }
}