package com.ecl.auth.offline;

import com.ecl.auth.AuthProvider;
import com.ecl.auth.AuthType;
import com.ecl.auth.OfflineSkin;
import com.ecl.auth.OfflineSkinStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires an offline account's local skin into a game launch: ensures the authlib-injector agent is
 * available, registers the player on the shared local Yggdrasil server, and produces the extra JVM
 * arguments that redirect the game's session-server calls to it.
 */
public final class OfflineSkinInjector {

    private OfflineSkinInjector() {
    }

    /** Prepare the JVM arguments and a lifecycle handle for one game process. */
    public static Injection prepare(AuthProvider auth, OfflineSkin skin) throws IOException {
        if (auth == null || skin == null || auth.getType() != AuthType.OFFLINE) {
            return Injection.empty();
        }
        String expectedIdentity = OfflineSkinStore.identityForOffline(auth.getUsername());
        if (!expectedIdentity.equalsIgnoreCase(skin.identity())) {
            throw new IOException("离线皮肤不属于当前账号");
        }
        Path jar = new AuthlibInjectorManager().ensureJar();
        OfflineSkinServer.Lease lease = OfflineSkinServer.acquire();
        OfflineSkinServer.Registration registration = null;
        try {
            OfflineSkinServer server = lease.server();
            registration = server.registerCharacter(
                    auth.getUUID(), auth.getUsername(), skin.pngFile(), skin.slim());
            return new Injection(List.of(
                    "-javaagent:" + jar.toAbsolutePath() + "=" + server.baseUrl(),
                    "-Dauthlibinjector.side=client"), lease, registration);
        } catch (IOException | RuntimeException failure) {
            if (registration != null) {
                registration.close();
            }
            lease.close();
            throw failure;
        }
    }

    /** JVM arguments plus ownership of the local server used by those arguments. */
    public static final class Injection implements AutoCloseable {
        private static final Injection EMPTY = new Injection(List.of(), null, null);

        private final List<String> jvmArgs;
        private final OfflineSkinServer.Lease lease;
        private final OfflineSkinServer.Registration registration;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Injection(List<String> jvmArgs, OfflineSkinServer.Lease lease,
                          OfflineSkinServer.Registration registration) {
            this.jvmArgs = List.copyOf(jvmArgs);
            this.lease = lease;
            this.registration = registration;
        }

        static Injection empty() {
            return EMPTY;
        }

        public List<String> jvmArgs() {
            return jvmArgs;
        }

        /** Keep the service alive until {@code process} exits. */
        public void closeWhen(Process process) {
            if (lease == null) {
                return;
            }
            process.onExit().whenComplete((ignored, failure) -> close());
        }

        @Override
        public void close() {
            if (lease != null && closed.compareAndSet(false, true)) {
                registration.close();
                lease.close();
            }
        }
    }
}
