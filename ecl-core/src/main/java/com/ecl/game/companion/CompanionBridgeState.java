package com.ecl.game.companion;

import java.util.UUID;

/** Compatibility and binding state shown by the ECL AI Assistant page. */
public record CompanionBridgeState(Status status, String modVersion, int protocolVersion,
                                   UUID boundPlayerUuid, String boundPlayerName, String message,
                                   boolean sharedDirectory) {
    public CompanionBridgeState {
        status = status == null ? Status.NOT_INSTALLED : status;
        modVersion = modVersion == null ? "" : modVersion;
        boundPlayerName = boundPlayerName == null ? "" : boundPlayerName;
        message = message == null ? "" : message;
    }

    public boolean canSubmit() {
        return status == Status.INSTALLED || status == Status.UNBOUND;
    }

    public enum Status {
        INSTALLED,
        NOT_INSTALLED,
        INCOMPATIBLE,
        UNBOUND
    }
}
