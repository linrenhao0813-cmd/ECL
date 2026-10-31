package com.ecl.ui;

import com.ecl.util.Messages;

/** Top-level launcher navigation destinations. */
enum AppView {
    HOME("/icons/ui/home.png", "⌂", "nav.home"),
    VERSIONS("/icons/ui/stone-block.png", "□", "nav.versions"),
    DOWNLOADS("/icons/ui/modrinth.png", "D", "nav.downloads"),
    SERVERS("/icons/ui/signal.png", "◈", "nav.servers"),
    SETTINGS("/icons/ui/gear.png", "⚙", "nav.settings");

    final String iconResource;
    final String fallbackIcon;
    final String labelKey;

    AppView(String iconResource, String fallbackIcon, String labelKey) {
        this.iconResource = iconResource;
        this.fallbackIcon = fallbackIcon;
        this.labelKey = labelKey;
    }

    String getLabel() {
        return Messages.get(labelKey);
    }
}
