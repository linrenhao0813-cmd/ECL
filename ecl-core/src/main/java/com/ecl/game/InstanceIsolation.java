package com.ecl.game;

/** Controls where mutable game data such as saves, configs and mods is stored. */
public enum InstanceIsolation {
    GLOBAL_SHARED,
    VERSION_ISOLATED,
    CUSTOM
}
