package com.ecl.util;

import ch.qos.logback.core.PropertyDefinerBase;
import com.ecl.ECLConfig;

import java.io.File;

/** Resolves Logback's directory with the same rules as launcher data. */
public final class LogDirectoryProperty extends PropertyDefinerBase {
    @Override
    public String getPropertyValue() {
        return new File(ECLConfig.getBaseDir(), "logs").getAbsolutePath();
    }
}
