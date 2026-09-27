package com.ecl.ui;

import com.ecl.game.DefaultGameRepository;
import com.ecl.game.PlaytimeTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentInstancesPaneTest {
    @TempDir
    Path root;

    @Test
    void showsTwoLatestLocalInstancesAndSkipsMissingOrCorruptHistory() throws Exception {
        DefaultGameRepository repository = new DefaultGameRepository(root.resolve("versions"), root);
        PlaytimeTracker tracker = new PlaytimeTracker();
        for (String id : List.of("older", "latest", "second", "never", "broken")) {
            Path instance = repository.instanceRoot(id);
            Files.createDirectories(instance);
            Files.writeString(instance.resolve(id + ".json"), "{}");
        }
        tracker.recordLaunch(repository.instanceRoot("older"), 1000);
        tracker.recordLaunch(repository.instanceRoot("latest"), 3000);
        tracker.recordLaunch(repository.instanceRoot("second"), 2000);
        Path brokenStats = repository.instanceRoot("broken").resolve(".ecl/config/playtime.json");
        Files.createDirectories(brokenStats.getParent());
        Files.writeString(brokenStats, "invalid json");

        assertEquals(List.of("latest", "second"), RecentInstancesPane.readRecent(repository, tracker)
                .stream().map(RecentInstancesPane.RecentInstance::id).toList());
    }

    @Test
    void metadataWithoutAnInstanceInTheConfiguredDirectoryDoesNotAppear() throws Exception {
        Path metadata = root.resolve("metadata/removed");
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("removed.json"), "{}");
        DefaultGameRepository repository = new DefaultGameRepository(root.resolve("metadata"), root.resolve("game"));
        assertTrue(RecentInstancesPane.readRecent(repository, new PlaytimeTracker()).isEmpty());
    }
}
