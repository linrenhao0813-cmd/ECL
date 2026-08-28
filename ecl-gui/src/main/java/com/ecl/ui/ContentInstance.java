package com.ecl.ui;

import java.io.File;

record ContentInstance(String profileId, String minecraftVersion, String loader,
                       File gameDirectory) {
}
