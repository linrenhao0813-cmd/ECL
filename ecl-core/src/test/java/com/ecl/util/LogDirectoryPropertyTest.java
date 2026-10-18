package com.ecl.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import com.ecl.ECLConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogDirectoryPropertyTest {
    @Test
    void activeAndRollingLogsUseTheLauncherDataDirectory() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        RollingFileAppender<?> appender = (RollingFileAppender<?>) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        Path directory = ECLConfig.getBaseDir().toPath().resolve("logs").toAbsolutePath();
        assertEquals(directory.resolve("ecl.log"), Path.of(appender.getFile()));
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();
        assertEquals(directory.resolve("ecl.%d{yyyy-MM-dd}.%i.log.gz"), Path.of(policy.getFileNamePattern()));
        assertTrue(appender.isStarted());
        assertTrue(Files.isRegularFile(directory.resolve("ecl.log")));
    }
}
