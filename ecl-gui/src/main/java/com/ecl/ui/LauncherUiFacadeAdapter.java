package com.ecl.ui;

/** Production adapter exposing only launch-facing UI state to coordinators. */
final class LauncherUiFacadeAdapter implements LaunchUiFacade {
    private final LauncherUI ui;

    LauncherUiFacadeAdapter(LauncherUI ui) {
        this.ui = ui;
    }

    @Override public String selectedVersion() { return ui.versionCombo.getValue(); }
    @Override public LoaderChoice requestedLoader() {
        return ui.loaderChoiceCombo == null ? LoaderChoice.VANILLA : ui.loaderChoiceCombo.getValue();
    }
    @Override public LoaderChoice loaderForProfile(String version) { return ui.loaderChoiceForProfile(version); }
    @Override public String authType() { return ui.authTypeCombo.getValue(); }
    @Override public String username() { return ui.usernameField.getText(); }
    @Override public String yggdrasilServer() { return ui.yggdrasilServerField.getText(); }
    @Override public String password() { return ui.passwordField.getText(); }
    @Override public String lastContentVersion() { return ui.lastContentVersion; }
    @Override public boolean isVersionDownloaded(String version) { return ui.versionManager.isVersionDownloaded(version); }
    @Override public void setStatus(String title, String detail) { ui.setStatus(title, detail); }
    @Override public void installSelectedLoader(Runnable continuation) { ui.installSelectedLoader(continuation); }
}
