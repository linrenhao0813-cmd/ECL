package com.ecl.ui;

import com.ecl.util.InstanceOperationLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionActionsTest {
    @Test
    void wizardRefreshPreservesMissingOrExistingLaunchSelection() {
        List<String> installed = List.of("1.20.1", "1.21.1-fabric");
        assertNull(VersionActions.chooseInstalledVersion(installed, null, false));
        assertNull(VersionActions.chooseInstalledVersion(installed, "removed-instance", false));
        assertEquals("1.21.1-fabric", VersionActions.chooseInstalledVersion(installed, "1.21.1-fabric", false));
    }

    @Test
    void lifecycleLockProtectsSecondPhaseInstanceDeletion(@TempDir Path root)
            throws Exception {
        Path instance = root.resolve("versions/example");
        Path operationLock = instance.resolve(".ecl/operation.lock");
        Files.createDirectories(operationLock.getParent());
        Files.writeString(instance.resolve("options.txt"), "settings");

        try (InstanceOperationLease lease = InstanceOperationLease.tryAcquire(instance)) {
            assertTrue(lease != null);
            VersionActions.deleteTreeWithin(root.resolve("versions"), instance, operationLock);
            assertTrue(Files.isRegularFile(operationLock));
            assertFalse(Files.exists(instance.resolve("options.txt")));

            lease.releaseLegacyLock();
            try (InstanceOperationLease competing = InstanceOperationLease.tryAcquire(instance)) {
                assertTrue(competing == null);
            }
            VersionActions.deleteTreeWithin(root.resolve("versions"), instance, null);
        }

        assertFalse(Files.exists(instance));
    }

    @Test
    void keepsPreferredVersionOnlyWhenItIsInstalled() {
        List<String> installed = List.of("1.20.1", "1.21.1-fabric");

        assertEquals("1.21.1-fabric",
                VersionActions.chooseInstalledVersion(installed, "1.21.1-fabric"));
        assertEquals("1.20.1",
                VersionActions.chooseInstalledVersion(installed, "1.21.5"));
    }

    @Test
    void leavesSelectionEmptyWhenNoInstanceIsInstalled() {
        assertNull(VersionActions.chooseInstalledVersion(List.of(), "1.21.5"));
    }
}
