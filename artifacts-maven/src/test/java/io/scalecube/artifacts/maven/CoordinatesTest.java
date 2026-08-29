package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoordinatesTest {

  @Test
  void shouldParseThreeParts() {
    final var coordinates = Coordinates.parse("com.foo:bar:1.0-SNAPSHOT");

    assertEquals("com.foo", coordinates.groupId(), "groupId");
    assertEquals("bar", coordinates.artifactId(), "artifactId");
    assertEquals("1.0-SNAPSHOT", coordinates.version(), "version");
  }

  @Test
  void shouldRejectWrongNumberOfParts() {
    assertThrows(IllegalArgumentException.class, () -> Coordinates.parse("com.foo:bar"));
    assertThrows(IllegalArgumentException.class, () -> Coordinates.parse("com.foo:bar:1.0:extra"));
    assertThrows(IllegalArgumentException.class, () -> Coordinates.parse(null));
  }

  @Test
  void shouldRejectBlankPart() {
    assertThrows(IllegalArgumentException.class, () -> Coordinates.parse("com.foo::1.0"));
    assertThrows(IllegalArgumentException.class, () -> Coordinates.parse(":bar:1.0"));
    assertThrows(IllegalArgumentException.class, () -> Coordinates.parse("com.foo:bar:"));
  }

  @Test
  void shouldDetectSnapshot() {
    assertTrue(Coordinates.parse("com.foo:bar:1.0-SNAPSHOT").snapshot(), "snapshot");
    assertFalse(Coordinates.parse("com.foo:bar:1.0").snapshot(), "snapshot");
  }

  @Test
  void shouldStripSnapshotSuffixForBaseVersion() {
    assertEquals("1.0", Coordinates.parse("com.foo:bar:1.0-SNAPSHOT").baseVersion(), "baseVersion");
    assertEquals("1.0", Coordinates.parse("com.foo:bar:1.0").baseVersion(), "baseVersion");
  }

  @Test
  void shouldBuildRepositoryLayoutDirectory() {
    assertEquals(
        "io/scalecube/my-group/my-artifact/2.1.0-SNAPSHOT",
        Coordinates.parse("io.scalecube.my-group:my-artifact:2.1.0-SNAPSHOT").directory(),
        "directory");
  }

  @Test
  void shouldBuildFileName() {
    final var coordinates = Coordinates.parse("com.foo:bar:1.0-SNAPSHOT");

    assertEquals("bar-1.0-SNAPSHOT.jar", coordinates.fileName("1.0-SNAPSHOT"), "fileName");
    assertEquals(
        "bar-1.0-20260225.142030-45.jar",
        coordinates.fileName("1.0-20260225.142030-45"),
        "fileName");
  }
}
