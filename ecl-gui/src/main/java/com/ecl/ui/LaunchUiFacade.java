package com.ecl.ui;

/** UI boundary consumed by launch orchestration; keeps validation independent of JavaFX fields. */
interface LaunchUiFacade {
    String selectedVersion();
    LoaderChoice requestedLoader();
    LoaderChoice loaderForProfile(String version);
    String authType();
    String username();
    String yggdrasilServer();
    String password();
    String lastContentVersion();
    boolean isVersionDownloaded(String version);
    void setStatus(String title, String detail);
    void installSelectedLoader(Runnable continuation);
}
