package com.ecl.launch;

/**
 * Receives decoded output lines from a running game process.
 *
 * <p>Lines arrive on the pump thread that reads the process pipes; implementations that touch the
 * UI must hop to the UI thread themselves. Removal during iteration is safe because the pump keeps
 * a snapshot of its subscribers.</p>
 */
@FunctionalInterface
public interface ProcessOutputListener {

    void onOutputLine(String line);
}