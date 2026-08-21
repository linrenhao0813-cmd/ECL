package com.ecl.cli;

import com.ecl.auth.DefaultAccountService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "default", description = "Select the default account.")
final class AccountDefaultCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "TYPE:uuid identity from account list")
    private String identity;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        new DefaultAccountService().setDefault(identity);
        EclCli.root(spec).print(Map.of("identity", identity, "default", true));
        return 0;
    }
}
