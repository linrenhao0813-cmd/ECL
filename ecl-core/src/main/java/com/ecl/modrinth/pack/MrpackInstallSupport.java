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
                listener.onStatus("警告：旧版文件已被用户修改，更新时予以保留: " + old.getKey());
            }
        }
        return warnings;
    }

    static PackManifest readInstalledManifest(Path instanceRoot, MrpackInstaller.Listener listener) {
        Path manifestFile = instanceRoot.resolve(PackManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            listener.onStatus("未找到旧整合包文件清单；本次更新不会删除旧版遗留文件");
            return new PackManifest("", Map.of());
        }
        try {
            return PackManifest.read(manifestFile);
        } catch (IOException error) {
            listener.onStatus("旧整合包文件清单无效；本次更新不会删除旧版遗留文件");
            return new PackManifest("", Map.of());
        }
    }

    record LoaderState(String parentProfile, String loaderId, String loaderVersion) {
    }
}
