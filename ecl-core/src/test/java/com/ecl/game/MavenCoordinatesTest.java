package com.ecl.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCoordinatesTest {

    @Test
    void recognizesSimpleThreePartCoordinates() {
        assertTrue(MavenCoordinates.isSimpleCoordinate("net.fabricmc:fabric-loader:0.19.3"));
        assertTrue(MavenCoordinates.isSimpleCoordinate("org.ow2.asm:asm:9.10.1"));
        assertTrue(MavenCoordinates.isSimpleCoordinate("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"));
        assertTrue(MavenCoordinates.isSimpleCoordinate("a-b_c.d:e+f-g:h1.2_3"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("net.fabricmc:fabric-loader:0.19.3:classifier"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("net.fabricmc:fabric-loader"));
        assertFalse(MavenCoordinates.isSimpleCoordinate(null));
        assertFalse(MavenCoordinates.isSimpleCoordinate(""));
        assertFalse(MavenCoordinates.isSimpleCoordinate("  "));
    }

    @Test
    void rejectsCoordinatesContainingPathSeparatorsOrTraversal() {
        assertFalse(MavenCoordinates.isSimpleCoordinate("net/fabricmc:fabric-loader:0.19.3"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("net.fabricmc:fabric\\loader:0.19.3"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("..:fabric-loader:0.19.3"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("net.fabricmc:..:0.19.3"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("net.fabricmc:fabric-loader:../0.19.3"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("net.fabricmc:fabric-loader:0.19.3/.."));
        assertFalse(MavenCoordinates.isSimpleCoordinate("a::1"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("a b:c:1"));
        assertFalse(MavenCoordinates.isSimpleCoordinate("a:b:c "));
    }

    @Test
    void mapsCoordinatesToRepositoryPaths() {
        assertEquals("net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar",
                MavenCoordinates.repositoryPath("net.fabricmc:fabric-loader:0.19.3"));
        assertEquals("org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
                MavenCoordinates.repositoryPath("org.ow2.asm:asm:9.10.1"));
        assertEquals("net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar",
                MavenCoordinates.repositoryPath("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"));
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoordinates.repositoryPath("not-a-coordinate"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoordinates.repositoryPath("a:b:c:d"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoordinates.repositoryPath("net/fabricmc:fabric-loader:0.19.3"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoordinates.repositoryPath("..:a:1"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoordinates.repositoryPath(null));
    }

    @Test
    void joinsRepositoryUrlWithPath() {
        assertEquals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar",
                MavenCoordinates.repositoryUrl(
                        "https://maven.fabricmc.net/", "net.fabricmc:fabric-loader:0.19.3"));
        assertEquals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar",
                MavenCoordinates.repositoryUrl(
                        "https://maven.fabricmc.net", "net.fabricmc:fabric-loader:0.19.3"));
    }
}
