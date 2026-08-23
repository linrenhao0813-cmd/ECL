package com.ecl.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaVersionRequirementTest {
    @Test
    void infersJava16ForMinecraft117AndItsSnapshots() {
        assertEquals(16, JavaVersionRequirement.inferFromVersionId("1.17"));
        assertEquals(16, JavaVersionRequirement.inferFromVersionId("1.17.1-custom"));
        assertEquals(16, JavaVersionRequirement.inferFromVersionId("21w19a"));
        assertEquals(17, JavaVersionRequirement.inferFromVersionId("21w37a"));
    }

    @Test
    void preservesModernRuntimeBoundaries() {
        assertEquals(17, JavaVersionRequirement.inferFromVersionId("1.20.4"));
        assertEquals(21, JavaVersionRequirement.inferFromVersionId("1.20.5-pre1"));
        assertEquals(21, JavaVersionRequirement.inferFromVersionId("24w14a"));
    }
}
