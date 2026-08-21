package com.ecl.cli;

import com.ecl.auth.OfflineSkinStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "skin-remove", description = "Remove the imported skin of an offline account.")
final class AccountSkinRemoveCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Offline player name")
    private String username;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        boolean removed = new OfflineSkinStore()
                .remove(OfflineSkinStore.identityForOffline(username));
        EclCli.root(spec).print(Map.of("username", username, "removed", removed));
        return removed ? 0 : 2;
    }
}
