package com.ecl.cli;

import picocli.CommandLine;

/** Shared usage behavior for command groups without a direct action. */
abstract class CommandGroupSupport implements Runnable {
    @Override
    public final void run() {
        CommandLine.usage(this, System.out);
    }
}
