package com.ecl.auth.offline;

import com.ecl.ECLConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Builds the authlib-injector compatible JSON documents served by the local skin server. */
final class OfflineSkinYggdrasilResponses {
    record Character(String uuid, String name, String textureHash, boolean slim) {
    }

    private OfflineSkinYggdrasilResponses() {
    }

    static JsonObject metadata(OfflineSkinTextureSigner signer) {
        JsonObject meta = new JsonObject();
        meta.addProperty("serverName", "ECL");
        meta.addProperty("implementationName", ECLConfig.LAUNCHER_NAME);
        meta.addProperty("implementationVersion", ECLConfig.LAUNCHER_VERSION);
        meta.addProperty("feature.non_email_login", true);
        JsonArray skinDomains = new JsonArray();
        skinDomains.add("127.0.0.1");
        skinDomains.add("localhost");
        JsonObject root = new JsonObject();
        root.addProperty("signaturePublickey", signer.publicKeyPem());
        root.add("skinDomains", skinDomains);
        root.add("meta", meta);
        return root;
    }

    static JsonObject status(int userCount) {
        JsonObject status = new JsonObject();
        status.addProperty("user.count", userCount);
        status.addProperty("token.count", 0);
        status.addProperty("pendingAuthentication.count", 0);
        return status;
    }

    static JsonObject simpleProfile(Character character) {
        JsonObject response = new JsonObject();
        response.addProperty("id", character.uuid());
        response.addProperty("name", character.name());
        return response;
    }

    static JsonObject completeProfile(Character character, String baseUrl, OfflineSkinTextureSigner signer) {
        JsonObject skinTexture = new JsonObject();
        skinTexture.addProperty("url", baseUrl + "/textures/" + character.textureHash());
        if (character.slim()) {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("model", "slim");
            skinTexture.add("metadata", metadata);
        }
        JsonObject textures = new JsonObject();
        textures.add("SKIN", skinTexture);
        JsonObject payload = new JsonObject();
        payload.addProperty("timestamp", System.currentTimeMillis());
        payload.addProperty("profileId", character.uuid());
        payload.addProperty("profileName", character.name());
        payload.add("textures", textures);
        String value = Base64.getEncoder().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject property = new JsonObject();
        property.addProperty("name", "textures");
        property.addProperty("value", value);
        property.addProperty("signature", signer.sign(value));
        JsonArray properties = new JsonArray();
        properties.add(property);
        JsonObject response = simpleProfile(character);
        response.add("properties", properties);
        return response;
    }
}
