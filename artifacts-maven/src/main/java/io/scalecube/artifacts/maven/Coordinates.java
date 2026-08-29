package io.scalecube.artifacts.maven;

/**
 * Parsed {@code groupId:artifactId:version} coordinate. Replaces the {@code spec.split(":")} that
 * was repeated in {@link Repository}, and adds the snapshot and base-version handling that {@link
 * JarResolver} needs to tell a locally installed build from a timestamped one.
 *
 * @param groupId group id, e.g. {@code com.example}
 * @param artifactId artifact id, e.g. {@code my-lib}
 * @param version version, e.g. {@code 1.2.3} or {@code 1.0-SNAPSHOT}
 */
public record Coordinates(String groupId, String artifactId, String version) {

  static final String SNAPSHOT = "-SNAPSHOT";

  public Coordinates {
    groupId = require(groupId, "groupId");
    artifactId = require(artifactId, "artifactId");
    version = require(version, "version");
  }

  /**
   * Parses a {@code groupId:artifactId:version} coordinate.
   *
   * @param spec artifact coordinate
   * @return parsed coordinates
   * @throws IllegalArgumentException if spec is not three non-blank parts
   */
  public static Coordinates parse(String spec) {
    if (spec == null) {
      throw new IllegalArgumentException("Wrong format: null");
    }
    final var split = spec.split(":", -1);
    if (split.length != 3) {
      throw new IllegalArgumentException("Wrong format: " + spec);
    }
    return new Coordinates(split[0], split[1], split[2]);
  }

  public boolean snapshot() {
    return version.endsWith(SNAPSHOT);
  }

  /**
   * Returns the version without the {@code -SNAPSHOT} suffix, which is the prefix that timestamped
   * builds are named after: {@code 1.0} for {@code 1.0-SNAPSHOT}. Returns the version unchanged for
   * a release.
   */
  public String baseVersion() {
    return snapshot() ? version.substring(0, version.length() - SNAPSHOT.length()) : version;
  }

  /**
   * Returns the repository-relative directory that holds every file of this version, for example
   * {@code com/foo/bar/1.0-SNAPSHOT}.
   *
   * @return directory path relative to the repository root
   */
  public String directory() {
    return groupId.replace('.', '/') + "/" + artifactId + "/" + version;
  }

  public String fileName(String resolvedVersion) {
    return artifactId + "-" + resolvedVersion + ".jar";
  }

  public String spec() {
    return groupId + ":" + artifactId + ":" + version;
  }

  @Override
  public String toString() {
    return spec();
  }

  private static String require(String token, String name) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Artifact " + name + " must not be blank");
    }
    return token;
  }
}
