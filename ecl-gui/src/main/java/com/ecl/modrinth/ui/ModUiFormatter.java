package com.ecl.modrinth.ui;

import com.ecl.modrinth.instance.ModInstanceContext;
import com.ecl.modrinth.model.DependencyType;
import com.ecl.modrinth.model.ModProject;
import com.ecl.modrinth.ui.viewmodel.ModBrowserViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text formatting shared by Modrinth UI components. */
final class ModUiFormatter {
    private ModUiFormatter() {
    }

    static String projectDetails(ModProject project) {
        List<String> parts = new ArrayList<>();
        if (!project.author().isBlank()) {
            parts.add(project.author());
        }
        parts.add("下载 " + project.downloads());
        if (!project.license().isBlank()) {
            parts.add(project.license());
        }
        if (!project.clientSide().isBlank()) {
            parts.add("客户端 " + project.clientSide());
        }
        if (!project.serverSide().isBlank()) {
            parts.add("服务端 " + project.serverSide());
        }
        return String.join(" · ", parts);
    }

    static String instanceText(ModInstanceContext context) {
        return context.minecraftVersion() + " · " + loaderName(context.loaderName())
                + " · " + context.profileId();
    }

    static String loaderName(String loader) {
        return switch (loader) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> "原版";
        };
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    static String normalizedChannel(String type) {
        String value = type == null ? "release" : type.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "beta" -> "beta";
            case "alpha" -> "alpha";
            default -> "release";
        };
    }

    static String channelLabel(String type) {
        return switch (normalizedChannel(type)) {
            case "beta" -> "Beta";
            case "alpha" -> "Alpha";
            default -> "Release";
        };
    }

    static String loaderDisplay(String loader) {
        if (loader == null || loader.isBlank()) {
            return "通用";
        }
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "quilt" -> "Quilt";
            default -> loader;
        };
    }

    static String dependencyIdentity(ModBrowserViewModel.DependencyProject item) {
        String projectId = item.dependency().projectId();
        if (projectId != null && !projectId.isBlank()) {
            return projectId;
        }
        String versionId = item.dependency().versionId();
        return versionId == null || versionId.isBlank() ? "未知依赖" : versionId;
    }

    static String dependencyTypeLabel(DependencyType type) {
        return switch (type) {
            case REQUIRED -> "必需依赖";
            case OPTIONAL -> "可选依赖";
            case EMBEDDED -> "内嵌依赖";
            case INCOMPATIBLE -> "不兼容";
            case UNKNOWN -> "其他依赖";
        };
    }
}
