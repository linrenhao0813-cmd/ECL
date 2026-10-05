package com.ecl.ui;

import com.ecl.ECLConfig;
import com.ecl.auth.MinecraftProfileSkinService;
import com.ecl.auth.OfflineSkinStore;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Shows the selected player's skin face and hat layer in the title bar. */
final class AccountAvatarPresenter {
    private static final int AVATAR_SIZE = 24;
    private static final long MAX_SKIN_BYTES = 1024 * 1024;
    private static final Rectangle2D FACE = new Rectangle2D(8, 8, 8, 8);
    private static final Rectangle2D HAT = new Rectangle2D(40, 8, 8, 8);
    private static final String[] STEVE_FACE = {
        "KKKKKKKK",
        "KBBBBBBK",
        "KBSSSSBK",
        "SSWUSWUS",
        "SSSSSSSS",
        "SSSTTSSS",
        "SSDDDDSS",
        "SSSSSSSS"
    };

    private final LauncherUI ui;
    private final MinecraftProfileSkinService profileSkins = new MinecraftProfileSkinService();
    private final Image steveSkin = createSteveSkin();
    private final ImageView face = layer(FACE);
    private final ImageView hat = layer(HAT);
    private final StackPane view = new StackPane(face, hat);
    private String selectedIdentity;
    private long requestId;

    AccountAvatarPresenter(LauncherUI ui) {
        this.ui = ui;
        view.setMinSize(AVATAR_SIZE, AVATAR_SIZE);
        view.setPrefSize(AVATAR_SIZE, AVATAR_SIZE);
        view.setMaxSize(AVATAR_SIZE, AVATAR_SIZE);
        view.getStyleClass().add("account-avatar");
        show(steveSkin);
    }

    StackPane view() {
        return view;
    }

    void update() {
        if (ui.authTypeCombo == null) {
            return;
        }
        String authType = ui.authTypeCombo.getValue();
        String username = ui.usernameField == null ? "" : ui.usernameField.getText();
        String uuid = ui.selectedMicrosoftAccount == null
                ? ui.settingsManager.get(ECLConfig.KEY_MICROSOFT_PROFILE_UUID)
                : ui.selectedMicrosoftAccount.uuid();
        String identity;
        if (LauncherUI.AUTH_OFFLINE.equals(authType)) {
            identity = LauncherUI.AUTH_OFFLINE + ":" + username;
        } else if (LauncherUI.AUTH_MICROSOFT.equals(authType)) {
            identity = LauncherUI.AUTH_MICROSOFT + ":" + uuid;
        } else {
            identity = String.valueOf(authType);
        }
        if (identity.equals(selectedIdentity)) {
            return;
        }
        selectedIdentity = identity;
        long request = ++requestId;
        show(steveSkin);
        if (LauncherUI.AUTH_OFFLINE.equals(authType) && username != null && !username.isBlank()) {
            ui.runAsync("ecl-offline-avatar", () -> loadOffline(request, username));
        } else if (LauncherUI.AUTH_MICROSOFT.equals(authType) && uuid != null && !uuid.isBlank()) {
            ui.runAsync("ecl-minecraft-avatar", () -> loadOfficial(request, uuid));
        }
    }

    void refresh() {
        selectedIdentity = null;
        update();
    }

    void showUploadedSkin(Path skin) {
        long request = ++requestId;
        ui.runAsync("ecl-uploaded-avatar", () -> {
            try {
                if (Files.size(skin) <= MAX_SKIN_BYTES) {
                    apply(request, Files.readAllBytes(skin));
                }
            } catch (IOException | RuntimeException failure) {
                LauncherUI.LOGGER.debug("Cannot show uploaded account avatar", failure);
                Platform.runLater(this::refresh);
            }
        });
    }

    private void loadOffline(long request, String username) {
        try {
            Optional<Path> skin = new OfflineSkinStore()
                    .find(OfflineSkinStore.identityForOffline(username)).map(found -> found.pngFile());
            if (skin.isPresent() && Files.size(skin.get()) <= MAX_SKIN_BYTES) {
                apply(request, Files.readAllBytes(skin.get()));
            }
        } catch (IOException | RuntimeException failure) {
            LauncherUI.LOGGER.debug("Cannot load offline account avatar", failure);
        }
    }

    private void loadOfficial(long request, String uuid) {
        try {
            profileSkins.load(uuid).ifPresent(png -> apply(request, png));
        } catch (IOException | RuntimeException failure) {
            LauncherUI.LOGGER.debug("Cannot load Minecraft profile avatar", failure);
        }
    }

    private void apply(long request, byte[] png) {
        Platform.runLater(() -> {
            if (request != requestId || ui.applicationStopping.get()) {
                return;
            }
            Image image = new Image(new ByteArrayInputStream(png));
            if (!image.isError() && image.getWidth() == 64
                    && (image.getHeight() == 64 || image.getHeight() == 32)) {
                show(image);
            }
        });
    }

    private void show(Image skin) {
        face.setImage(skin);
        hat.setImage(skin);
    }

    private static ImageView layer(Rectangle2D viewport) {
        ImageView image = new ImageView();
        image.setViewport(viewport);
        image.setFitWidth(AVATAR_SIZE);
        image.setFitHeight(AVATAR_SIZE);
        image.setSmooth(false);
        return image;
    }

    private static Image createSteveSkin() {
        WritableImage skin = new WritableImage(64, 64);
        PixelWriter writer = skin.getPixelWriter();
        for (int y = 0; y < STEVE_FACE.length; y++) {
            for (int x = 0; x < STEVE_FACE[y].length(); x++) {
                writer.setArgb(8 + x, 8 + y, steveColor(STEVE_FACE[y].charAt(x)));
            }
        }
        return skin;
    }

    private static int steveColor(char pixel) {
        return switch (pixel) {
            case 'K' -> 0xff25170f;
            case 'B' -> 0xff4b2d1b;
            case 'S' -> 0xffb97b59;
            case 'W' -> 0xffedf2ed;
            case 'U' -> 0xff356d9a;
            case 'T' -> 0xffa46a4f;
            case 'D' -> 0xff68402b;
            default -> throw new IllegalArgumentException("Unknown Steve avatar pixel: " + pixel);
        };
    }
}
