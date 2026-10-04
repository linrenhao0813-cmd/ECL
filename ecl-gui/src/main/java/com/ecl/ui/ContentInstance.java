package com.ecl.ui;

import java.io.File;
import java.util.UUID;

record ContentInstance(String profileId, String minecraftVersion, String loader, UUID instanceId,
                       File gameDirectory) {
}
