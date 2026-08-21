package com.ecl.cli;

import com.ecl.auth.AuthAccount;
import com.ecl.auth.DefaultAccountService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "add-offline", description = "Add an offline account.")
final class AccountAddOfflineCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Player name")
    private String username;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        AuthAccount account = new DefaultAccountService().addOffline(username).withoutSecrets();
        EclCli.root(spec).print(account);
        return 0;
    }
}
