package com.ecl.cli;

import picocli.CommandLine.Command;

@Command(name = "account", description = "Manage launcher accounts.",
        subcommands = {AccountListCommand.class, AccountAddOfflineCommand.class,
                AccountRemoveCommand.class, AccountDefaultCommand.class,
                AccountSkinCommand.class, AccountSkinRemoveCommand.class})
final class AccountCommand extends CommandGroupSupport {
}
