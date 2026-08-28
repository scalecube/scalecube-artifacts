package io.scalecube.artifacts.maven;

import java.util.regex.Pattern;

/**
 * Parsed {@code groupId:artifactId:version} coordinate.
 *
 * <p>Every token is checked against an allow-list before it is concatenated into a filesystem path
 * or a URL, so a malformed coordinate cannot escape the local repository or reshape the request.
 *
 * @param groupId group id, e.g. {@code com.example}
 * @param artifactId artifact id, e.g. {@code my-lib}
 * @param version version, e.g. {@code 1.2.3} or {@code 1.0-SNAPSHOT}
 */
public record Coordinates(String groupId, String artifactId, String version) {

  static final String SNAPSHOT = "-SNAPSHOT";

  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._+-]+");

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
   * @throws IllegalArgumentException if {@code spec} is not three non-blank, well-formed tokens
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

  /** {@code 1.0} for {@code 1.0-SNAPSHOT}; the version itself for a release. */
  public String baseVersion() {
    return snapshot() ? version.substring(0, version.length() - SNAPSHOT.length()) : version;
  }

  /** The repository-relative directory holding every file of this version. */
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
    if (!TOKEN.matcher(token).matches() || token.contains("..")) {
      throw new IllegalArgumentException("Illegal artifact " + name + ": '" + token + "'");
    }
    return token;
  }
}
