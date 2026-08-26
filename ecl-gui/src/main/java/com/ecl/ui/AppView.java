package com.ecl.ui;

import com.ecl.util.Messages;

/** Top-level launcher navigation destinations. */
enum AppView {
    SAVES("/icons/ui/grass-block.png", "S", "nav.saves"),
    DOWNLOADS("/icons/ui/log.png", "D", "nav.downloads"),
    HOME("/icons/ui/home.png", "⌂", "nav.home"),
    VERSIONS("/icons/ui/stone-block.png", "□", "nav.versions"),
    MODRINTH("/icons/ui/modrinth.png", "◎", "nav.modrinth"),
    SERVERS("/icons/ui/signal.png", "◈", "nav.servers"),
    LOGS("/icons/ui/log.png", "▤", "nav.logs"),
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
