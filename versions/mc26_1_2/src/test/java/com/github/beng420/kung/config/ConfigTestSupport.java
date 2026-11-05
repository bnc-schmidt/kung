package com.github.beng420.kung.config;

import java.io.StringReader;

final class ConfigTestSupport {
    private ConfigTestSupport() { }

    static KungConfig read(String json) {
        return KungConfig.read(new StringReader(json));
    }
}
