package com.ecl.server;

import com.ecl.util.Messages;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.function.Consumer;

/** Performs connect, clipboard, and external-browser actions for selected servers. */
final class ServerBrowserActions {
    private final Consumer<String> statusConsumer;
    private final Consumer<String> connectConsumer;

    ServerBrowserActions(Consumer<String> statusConsumer, Consumer<String> connectConsumer) {
        this.statusConsumer = statusConsumer;
        this.connectConsumer = connectConsumer;
    }

    void connect(PublicServer server) {
        if (server != null) {
            connectConsumer.accept(server.address());
        }
    }

    void copyAddress(PublicServer server) {
        if (server == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(server.address());
        Clipboard.getSystemClipboard().setContent(content);
        statusConsumer.accept(Messages.format("server.status.copied", server.address()));
    }

    void openWebsite(PublicServer server) {
        if (server == null || server.websiteUri() == null) {
            return;
        }
        URI website = server.websiteUri();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(website);
            } else {
                statusConsumer.accept(Messages.format("server.status.browserUnsupported", website));
            }
        } catch (IOException | SecurityException error) {
            statusConsumer.accept(Messages.format("server.status.browserFailed", website));
        }
    }
}
