package com.ecl.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LaunchUiFacadeTest {
    @Test
    void facadeCarriesLaunchSelectionWithoutJavaFxControls() {
        LaunchUiFacade facade = new FakeFacade();

        assertEquals("1.21.1", facade.selectedVersion());
        assertEquals(LoaderChoice.FABRIC, facade.requestedLoader());
        assertEquals(LoaderChoice.VANILLA, facade.loaderForProfile("1.21.1"));
        assertNotEquals(facade.requestedLoader(), facade.loaderForProfile("1.21.1"));
    }

    private static final class FakeFacade implements LaunchUiFacade {
        @Override public String selectedVersion() { return "1.21.1"; }
        @Override public LoaderChoice requestedLoader() { return LoaderChoice.FABRIC; }
        @Override public LoaderChoice loaderForProfile(String version) { return LoaderChoice.VANILLA; }
        @Override public String authType() { return LauncherUI.AUTH_OFFLINE; }
        @Override public String username() { return "Player"; }
        @Override public String yggdrasilServer() { return ""; }
        @Override public String password() { return ""; }
        @Override public String lastContentVersion() { return ""; }
        @Override public boolean isVersionDownloaded(String version) { return true; }
        @Override public void setStatus(String title, String detail) { }
        @Override public void installSelectedLoader(Runnable continuation) { }
    }
}
