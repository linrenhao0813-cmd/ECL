package com.ecl.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ecl.util.HttpUtil;
import com.google.gson.JsonObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftAuthTest {
    @Test
    void slowDownPollingIntervalIsCapped() {
        assertEquals(10, MicrosoftAuth.nextDevicePollInterval(5));
        assertEquals(60, MicrosoftAuth.nextDevicePollInterval(58));
        assertEquals(60, MicrosoftAuth.nextDevicePollInterval(60));
        assertEquals(60, MicrosoftAuth.nextDevicePollInterval(Integer.MAX_VALUE));
    }

    @Test
    void returnsAConsistentCachedSessionSnapshot() {
        MicrosoftAuth.CachedSession cached = new MicrosoftAuth.CachedSession(
                "refresh", "access", 123L, "Player", "uuid");
        MicrosoftAuth auth = new MicrosoftAuth(cached, null);

        assertEquals(cached, auth.getCachedSession());
    }

    @Test
    void logoutInvalidatesAnInFlightLoginBeforeItCanCommitState() throws Exception {
        MicrosoftAuth auth = new MicrosoftAuth();
        long generation = auth.beginLogin();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread loginThread = new Thread(() -> {
            ready.countDown();
            try {
                if (!commit.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to commit login state");
                }
                auth.commitAuthenticatedSession(generation, "Player", "uuid", "access", 123L);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        loginThread.start();

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        auth.logout();
        commit.countDown();
        loginThread.join(5_000L);

        assertFalse(loginThread.isAlive());
        assertInstanceOf(MicrosoftAuth.LoginCancelledException.class, failure.get());
        assertFalse(auth.isLoggedIn());
        MicrosoftAuth.CachedSession session = auth.getCachedSession();
        assertNull(session.refreshToken());
        assertNull(session.accessToken());
        assertNull(session.username());
        assertNull(session.uuid());
    }

    @Test
    void refreshTokenCompletesTheMicrosoftXboxAndMinecraftFlow() {
        FakeAuthTransport transport = new FakeAuthTransport();
        List<String> statuses = new ArrayList<>();
        MicrosoftAuth auth = new MicrosoftAuth(
                new MicrosoftAuth.CachedSession("saved-refresh", null, 0, null, null),
                new MicrosoftAuth.LoginListener() {
                    @Override
                    public void onStatus(String message) {
                        statuses.add(message);
                    }
                },
                new MinecraftServicesClient(transport), new XboxLiveAuthClient(transport),
                new MicrosoftOAuthClient(transport));

        auth.login();

        assertTrue(auth.isLoggedIn());
        assertEquals("TestPlayer", auth.getUsername());
        assertEquals("player-uuid", auth.getUUID());
        assertEquals("minecraft-access", auth.getAccessToken());
        assertEquals("rotated-refresh", auth.getRefreshToken());
        assertEquals(List.of("oauth-refresh", "xbox-auth", "xsts-auth", "minecraft-login",
                "entitlements", "profile"), transport.requests);
        assertTrue(statuses.getLast().contains("TestPlayer"));
    }

    private static final class FakeAuthTransport implements AuthHttpTransport {
        private final List<String> requests = new ArrayList<>();

        @Override
        public HttpUtil.Response postForm(String url, Map<String, String> form) {
            requests.add("oauth-refresh");
            assertEquals("saved-refresh", form.get("refresh_token"));
            return response(200, "{\"access_token\":\"microsoft-access\","
                    + "\"refresh_token\":\"rotated-refresh\"}");
        }

        @Override
        public HttpUtil.Response postJson(String url, JsonObject body) {
            if (url.contains("user.auth.xboxlive.com")) {
                requests.add("xbox-auth");
                return response(200, xboxToken("xbox-token", "user-hash"));
            }
            if (url.contains("xsts.auth.xboxlive.com")) {
                requests.add("xsts-auth");
                return response(200, xboxToken("xsts-token", "user-hash"));
            }
            requests.add("minecraft-login");
            assertEquals("XBL3.0 x=user-hash;xsts-token",
                    body.get("identityToken").getAsString());
            return response(200, "{\"access_token\":\"minecraft-access\",\"expires_in\":3600}");
        }

        @Override
        public HttpUtil.Response getBearer(String url, String bearerToken) {
            assertEquals("minecraft-access", bearerToken);
            if (url.contains("entitlements")) {
                requests.add("entitlements");
                return response(200, "{\"items\":[{\"name\":\"game_minecraft\"}]}");
            }
            requests.add("profile");
            return response(200, "{\"name\":\"TestPlayer\",\"id\":\"player-uuid\"}");
        }

        private static String xboxToken(String token, String userHash) {
            return "{\"Token\":\"" + token + "\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\""
                    + userHash + "\"}]}}";
        }

        private static HttpUtil.Response response(int status, String body) {
            return new HttpUtil.Response(status, body, "https://test.invalid", Map.of());
        }
    }
}
