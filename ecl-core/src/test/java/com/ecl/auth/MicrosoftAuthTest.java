package com.ecl.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosoftAuthTest {
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
}
