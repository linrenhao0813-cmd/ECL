package com.ecl.auth;

import java.nio.file.Path;

/**
 * A locally imported skin bound to an offline account.
 *
 * <p>Unlike {@link MinecraftSkinService} (which uploads to Mojang for premium accounts), an
 * offline skin never leaves the machine: the PNG is copied into the launcher's data directory and
 * served to the game at launch time through the local Yggdrasil skin service.</p>
 *
 * @param identity canonical offline account identity that owns this skin
 * @param pngFile the validated PNG skin file stored in the launcher data directory
 * @param variant the character model (classic wide arms / slim arms)
 */
public record OfflineSkin(String identity, Path pngFile, MinecraftSkinService.Variant variant) {

    public OfflineSkin {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("Offline account identity is required");
        }
        if (pngFile == null) {
            throw new IllegalArgumentException("Skin PNG file is required");
        }
        variant = variant == null ? MinecraftSkinService.Variant.CLASSIC : variant;
    }

    /** Whether this skin uses the slim (Alex) model. */
    public boolean slim() {
        return variant == MinecraftSkinService.Variant.SLIM;
    }
}
