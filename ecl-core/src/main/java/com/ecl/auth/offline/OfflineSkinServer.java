package com.ecl.auth.offline;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal authlib-injector compatible Yggdrasil server that serves locally imported offline
 * skins to the running game. The protocol, registry, signing, and lease responsibilities live in
 * dedicated package-private collaborators; this class preserves the established integration API.
 */
public final class OfflineSkinServer implements AutoCloseable {
    private final HttpServer server;
    private final OfflineSkinCharacterRegistry registry = new OfflineSkinCharacterRegistry();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String baseUrl;

    OfflineSkinServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/", new OfflineSkinHttpHandler(registry, new OfflineSkinTextureSigner(), baseUrl, port));
        server.setExecutor(null);
        server.start();
    }

    /** Acquire the process-wide server until the returned lease is closed. */
    static Lease acquire() throws IOException {
        return new Lease(OfflineSkinServerLeases.acquire());
    }

    public String baseUrl() { return baseUrl; }

    /** Register (or replace) the character served for this offline player. */
    public synchronized Registration registerCharacter(String uuid, String username, Path skinPng, boolean slim)
            throws IOException {
        return new Registration(this, registry.register(uuid, username, skinPng, slim));
    }

    @Override public void close() {
        OfflineSkinServerLeases.clear(this);
        stopServer();
    }

    boolean isClosed() { return closed.get(); }
    void stopServer() { if (closed.compareAndSet(false, true)) server.stop(0); }

    /** One active game process' ownership of the shared skin server. */
    static final class Lease implements AutoCloseable {
        private final OfflineSkinServer server;
        private final AtomicBoolean released = new AtomicBoolean();
        private Lease(OfflineSkinServer server) { this.server = server; }
        OfflineSkinServer server() { return server; }
        @Override public void close() {
            if (released.compareAndSet(false, true)) OfflineSkinServerLeases.release(server);
        }
    }

    /** One running game's registration of a character and its texture. */
    static final class Registration implements AutoCloseable {
        private final OfflineSkinServer server;
        private final OfflineSkinYggdrasilResponses.Character character;
        private final AtomicBoolean removed = new AtomicBoolean();
        private Registration(OfflineSkinServer server, OfflineSkinYggdrasilResponses.Character character) {
            this.server = server;
            this.character = character;
        }
        @Override public void close() {
            if (removed.compareAndSet(false, true)) server.registry.unregister(character);
        }
    }

    static String queryParameter(String query, String name) {
        return OfflineSkinHttpHandler.queryParameter(query, name);
    }
}
