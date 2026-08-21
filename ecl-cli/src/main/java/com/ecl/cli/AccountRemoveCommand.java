package com.ecl.cli;

import com.ecl.auth.DefaultAccountService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "remove", description = "Remove an account by identity.")
final class AccountRemoveCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "TYPE:uuid identity from account list")
    private String identity;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        boolean removed = new DefaultAccountService().remove(identity);
        EclCli.root(spec).print(Map.of("identity", identity, "removed", removed));
        return removed ? 0 : 2;
    }
}
