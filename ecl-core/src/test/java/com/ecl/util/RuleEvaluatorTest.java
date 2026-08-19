package com.ecl.util;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEvaluatorTest {
    @Test
    void appliesLastMatchingRule() {
        var rules = JsonParser.parseString("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"windows\"}}]").getAsJsonArray();
        assertFalse(RuleEvaluator.isAllowed(rules));
        assertTrue(RuleEvaluator.isAllowed(null));
    }

    @Test
    void evaluatesFeatureRequirements() {
        var rules = JsonParser.parseString("[{\"action\":\"allow\",\"features\":{\"has_custom_resolution\":true}}]")
                .getAsJsonArray();

        assertFalse(RuleEvaluator.isAllowed(rules));
        assertTrue(RuleEvaluator.isAllowed(rules, Map.of("has_custom_resolution", true)));
    }

    @Test
    void rejectsAnIncompatibleArchitecture() {
        String actualArch = System.getProperty("os.arch", "").toLowerCase();
        String incompatibleArch = actualArch.contains("64") ? "x86" : "x86_64";
        var rules = JsonParser.parseString("[{\"action\":\"allow\",\"os\":{\"arch\":\""
                + incompatibleArch + "\"}}]").getAsJsonArray();

        assertFalse(RuleEvaluator.isAllowed(rules));
    }

    @Test
    void doesNotTreatArm32AsX86() {
        assertFalse(RuleEvaluator.matchesArchitecture("x86", "armhf"));
        assertFalse(RuleEvaluator.matchesArchitecture("x86", "armv7l"));
        assertTrue(RuleEvaluator.matchesArchitecture("x86", "i686"));
        assertTrue(RuleEvaluator.matchesArchitecture("x86_64", "amd64"));
    }
}
