package com.mojang.logging;

import org.slf4j.Logger;

public final class LogUtils {
    private static final Logger LOGGER = new Logger() {
    };

    private LogUtils() {
    }

    public static Logger getLogger() {
        return LOGGER;
    }
}
