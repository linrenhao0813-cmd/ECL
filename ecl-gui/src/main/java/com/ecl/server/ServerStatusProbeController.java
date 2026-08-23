package com.ecl.server;

import com.ecl.util.ThreadFactories;
import javafx.application.Platform;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Probes public server availability without coupling network work to the view. */
final class ServerStatusProbeController implements AutoCloseable {
    private static final int MAX_PROBES = 32;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            4, ThreadFactories.daemon("ecl-server-status"));
    private final Set<String> probingAddresses = ConcurrentHashMap.newKeySet();

    boolean isProbing(String address) {
        return probingAddresses.contains(address);
    }

    void probe(Collection<PublicServer> servers,
               Map<String, ServerStatus> statuses,
               BooleanSupplier closed,
               Runnable refreshView) {
        if (closed.getAsBoolean() || Boolean.getBoolean("ecl.snapshot")) {
            return;
        }
        int scheduled = 0;
        for (PublicServer server : servers) {
            ServerStatus existing = statuses.get(server.address());
            if ((existing != null && existing.state() != ServerStatusState.UNKNOWN)
                    || server.address().isBlank()
                    || !probingAddresses.add(server.address())) {
                continue;
            }
            scheduled++;
            executor.execute(() -> {
                ServerStatus status = ServerStatusService.fetch(server);
                if (!closed.getAsBoolean()) {
                    statuses.put(server.address(), status);
                }
                probingAddresses.remove(server.address());
                if (!closed.getAsBoolean()) {
                    Platform.runLater(refreshView);
                }
            });
            if (scheduled >= MAX_PROBES) {
                break;
            }
        }
        if (scheduled > 0) {
            refreshView.run();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
