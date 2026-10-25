package com.ecl.modrinth.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** Static helpers shared by mrpack installation and update flows. */
final class MrpackInstallSupport {
    private MrpackInstallSupport() {
    }

    static int stagePackChanges(PackUpdateTransaction transaction, Path instanceRoot,
                                Path staging, PackManifest oldManifest,
                                PackManifest newManifest, MrpackInstaller.Listener listener)
            throws IOException {
        for (String relative : newManifest.files().keySet()) {
            com.ecl.util.ManagedPackPathPolicy.requireManagedRelativePath(relative);
            transaction.stageReplacement(
                    PackManifest.resolve(staging, relative),
                    PackManifest.resolve(instanceRoot, relative));
        }
        int warnings = 0;
        for (Map.Entry<String, String> old : oldManifest.files().entrySet()) {
            if (newManifest.files().containsKey(old.getKey())) {
                continue;
            }
            Path target = PackManifest.resolve(instanceRoot, old.getKey());
            if (!Files.exists(target)) {
                continue;
            }
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && PackManifest.sha512(target).equalsIgnoreCase(old.getValue())) {
                transaction.stageDeletion(target);
            } else {
                warnings++;
                listener.onWarning("警告：旧版文件已被用户修改，更新时予以保留: " + old.getKey());
            }
        }
        return warnings;
    }

    static PackManifest readInstalledManifest(Path instanceRoot, MrpackInstaller.Listener listener) throws IOException {
        return PackManifest.read(PackManifest.resolve(instanceRoot, PackManifest.FILE_NAME));
    }

    record LoaderState(String parentProfile, String loaderId, String loaderVersion) {
    }
}
