package com.ecl.cli;

import com.ecl.auth.MinecraftSkinService;
import com.ecl.auth.OfflineSkin;
import com.ecl.auth.OfflineSkinStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "skin", description = "Import a local PNG skin for an offline account.")
final class AccountSkinCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Offline player name")
    private String username;

    @Parameters(index = "1", description = "PNG skin file (64x64 or 64x32)")
    private File pngFile;

    @Option(names = "--slim", description = "Use the slim (Alex) model")
    private boolean slim;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws IOException {
        OfflineSkinStore store = new OfflineSkinStore();
        String identity = OfflineSkinStore.identityForOffline(username);
        OfflineSkin skin = store.importSkin(identity, pngFile.toPath(),
                slim ? MinecraftSkinService.Variant.SLIM
                        : MinecraftSkinService.Variant.CLASSIC);
        EclCli.root(spec).print(Map.of(
                "username", username,
                "variant", slim ? "slim" : "classic",
                "skinFile", skin.pngFile().toAbsolutePath().toString(),
                "imported", true));
        return 0;
    }
}
