package com.ecl.cli;

import com.ecl.auth.AuthAccount;
import com.ecl.auth.DefaultAccountService;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "list", description = "List accounts without exposing credentials.")
final class AccountListCommand implements Callable<Integer> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        List<AuthAccount> accounts = new DefaultAccountService().list().stream()
                .map(AuthAccount::withoutSecrets).toList();
        EclCli root = EclCli.root(spec);
        root.print(root.jsonOutput() ? Map.of("accounts", accounts) : accounts);
        return 0;
    }
}
