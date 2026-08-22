package com.ecl.launcher;

import com.ecl.util.HttpUtil;
import com.ecl.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Queries Fabric, Quilt, Forge and NeoForge version metadata. */
final class LoaderMetadataClient {
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3";
    private static final String FORGE_META =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String NEOFORGE_META =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";

    List<String> listVersions(String minecraftVersion, ModLoaderInstaller.Loader loader)
            throws IOException {
        return switch (loader) {
            case FABRIC, QUILT -> listProfileLoaderVersions(minecraftVersion, loader);
            case FORGE -> listMavenVersions(FORGE_META).stream()
                    .filter(version -> version.startsWith(minecraftVersion + "-"))
                    .map(version -> version.substring(minecraftVersion.length() + 1))
                    .toList();
            case NEOFORGE -> {
                String prefix = neoForgePrefix(minecraftVersion);
                yield listMavenVersions(NEOFORGE_META).stream()
                        .filter(version -> version.startsWith(prefix))
                        .toList();
            }
        };
    }

    String profileUrl(String minecraftVersion, ModLoaderInstaller.Loader loader,
                      String loaderVersion) {
        String base = loader == ModLoaderInstaller.Loader.FABRIC ? FABRIC_META : QUILT_META;
        return base + "/versions/loader/" + encode(minecraftVersion) + "/"
                + encode(loaderVersion) + "/profile/json";
    }

    private List<String> listProfileLoaderVersions(
            String minecraftVersion, ModLoaderInstaller.Loader loader) throws IOException {
        String base = loader == ModLoaderInstaller.Loader.FABRIC ? FABRIC_META : QUILT_META;
        String body = HttpUtil.get(base + "/versions/loader/" + encode(minecraftVersion));
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonArray()) {
            throw new IOException(loader.displayName() + " 元数据格式无效");
        }
        List<String> stableVersions = new ArrayList<>();
        List<String> unstableVersions = new ArrayList<>();
        for (JsonElement item : parsed.getAsJsonArray()) {
            JsonObject object = item.getAsJsonObject();
            JsonObject loaderObject = object.has("loader") && object.get("loader").isJsonObject()
                    ? object.getAsJsonObject("loader") : object;
            String version = JsonUtil.getString(loaderObject, "version", "");
            if (!version.isBlank()) {
                boolean stable = !loaderObject.has("stable")
                        || loaderObject.get("stable").getAsBoolean();
                (stable ? stableVersions : unstableVersions).add(version);
            }
        }
        stableVersions.sort(LoaderMetadataClient::compareVersionsDescending);
        unstableVersions.sort(LoaderMetadataClient::compareVersionsDescending);
        List<String> versions = new ArrayList<>(stableVersions);
        versions.addAll(unstableVersions);
        if (versions.isEmpty()) {
            throw new IOException("没有找到兼容 Minecraft " + minecraftVersion + " 的 "
                    + loader.displayName() + " 版本");
        }
        return versions;
    }

    private static List<String> listMavenVersions(String metadataUrl) throws IOException {
        String xml = HttpUtil.get(metadataUrl);
        List<String> versions = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList versionNodes = document.getElementsByTagName("version");
            for (int index = 0; index < versionNodes.getLength(); index++) {
                String value = versionNodes.item(index).getTextContent().trim();
                if (!value.isBlank()) {
                    versions.add(value);
                }
            }
        } catch (ParserConfigurationException | SAXException failure) {
            throw new IOException("加载器版本元数据不是有效的 XML: " + metadataUrl, failure);
        }
        versions.sort(LoaderMetadataClient::compareVersionsDescending);
        return versions;
    }

    private static String neoForgePrefix(String minecraftVersion) throws IOException {
        String[] parts = minecraftVersion.split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) {
            throw new IOException("NeoForge 不支持该 Minecraft 版本格式: " + minecraftVersion);
        }
        String patch = parts.length >= 3 ? parts[2].replaceAll("\\D.*$", "") : "0";
        return parts[1] + "." + (patch.isBlank() ? "0" : patch) + ".";
    }

    static int compareVersionsDescending(String left, String right) {
        boolean leftPrerelease = isPrerelease(left);
        boolean rightPrerelease = isPrerelease(right);
        if (leftPrerelease != rightPrerelease) {
            return leftPrerelease ? 1 : -1;
        }
        Matcher leftNumbers = Pattern.compile("\\d+").matcher(stableCore(left));
        Matcher rightNumbers = Pattern.compile("\\d+").matcher(stableCore(right));
        boolean leftHasNumber = leftNumbers.find();
        boolean rightHasNumber = rightNumbers.find();
        while (leftHasNumber || rightHasNumber) {
            int leftValue = leftHasNumber ? Integer.parseInt(leftNumbers.group()) : 0;
            int rightValue = rightHasNumber ? Integer.parseInt(rightNumbers.group()) : 0;
            int comparison = Integer.compare(rightValue, leftValue);
            if (comparison != 0) {
                return comparison;
            }
            leftHasNumber = leftNumbers.find();
            rightHasNumber = rightNumbers.find();
        }
        return right.compareToIgnoreCase(left);
    }

    private static String stableCore(String version) {
        return version.split("(?i)[._+-]?(?:alpha|beta|rc|snapshot)", 2)[0];
    }

    private static boolean isPrerelease(String version) {
        String value = version.toLowerCase(Locale.ROOT);
        return value.contains("alpha") || value.contains("beta") || value.contains("-rc")
                || value.contains("snapshot");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
