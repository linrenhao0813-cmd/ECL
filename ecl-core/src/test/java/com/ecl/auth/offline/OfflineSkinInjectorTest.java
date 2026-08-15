package com.ecl.auth.offline;

import com.ecl.auth.MicrosoftAuth;
import com.ecl.auth.MinecraftSkinService;
import com.ecl.auth.OfflineAuth;
import com.ecl.auth.OfflineSkin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineSkinInjectorTest {

    @Test
    void rejectsSkinOwnedByAnotherOfflineAccount(@TempDir Path directory) {
        OfflineSkin skin = new OfflineSkin("OFFLINE:not-steve", directory.resolve("skin.png"),
                MinecraftSkinService.Variant.CLASSIC);

        assertThrows(IOException.class,
                () -> OfflineSkinInjector.prepare(new OfflineAuth("Steve"), skin));
    }

    @Test
    void ignoresOfflineSkinForMicrosoftAccount(@TempDir Path directory) throws Exception {
        OfflineSkin skin = new OfflineSkin("OFFLINE:any", directory.resolve("skin.png"),
                MinecraftSkinService.Variant.CLASSIC);

        try (OfflineSkinInjector.Injection injection = OfflineSkinInjector.prepare(
                new MicrosoftAuth(), skin)) {
            assertTrue(injection.jvmArgs().isEmpty());
        }
    }
}
