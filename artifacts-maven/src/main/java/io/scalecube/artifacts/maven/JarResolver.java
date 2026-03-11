package io.scalecube.artifacts.maven;

import static io.scalecube.artifacts.maven.Repository.localFile;
import static io.scalecube.artifacts.maven.Repository.remoteUri;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Component that manages local storage of JAR files within the maven repository structure. Handles
 * checksum verification and atomic file moves to ensure repository integrity.
 */
public class JarResolver {

  private final Fetcher fetcher;

  public JarResolver(Fetcher fetcher) {
    this.fetcher = fetcher;
  }

  /**
   * Resolves JAR from the remote repository. Performs SHA-1 verification and handles SNAPSHOT
   * aliasing (timestamped to -SNAPSHOT).
   *
   * @param repository artifact repository
   * @param metadata artifact metadata
   * @return future completing with the final local {@link Path} of the JAR
   */
  public CompletableFuture<Path> resolveJar(Repository repository, Metadata metadata) {
    try {
      final var groupId = metadata.groupId().replace(".", "/");
      final var artifactId = metadata.artifactId();
      final var version = getVersion(metadata); // e.g., "1.2.3" or "2.1.0-SNAPSHOT"
      final var spec = String.join(":", groupId, artifactId, version);
      final var filename = getFilename(metadata, artifactId, version);

      final var uri = remoteUri(repository, spec, filename);
      final var target = localFile(repository, spec, filename);

      final var uriSha1 = remoteUri(repository, spec, filename + ".sha1");
      final var targetSha1 = localFile(repository, spec, filename + ".sha1");

      return fetcher
          .get(uri, repository.authz(), target.getParent())
          .thenCombine(
              fetcher.get(uriSha1, repository.authz(), target.getParent()),
              (tmp, tmpSha1) -> {
                try {
                  final var actualSha1 = computeSha1(tmp);
                  final var expectedSha1 = expectedSha1(tmpSha1);

                  if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                    throw new IOException("Checksum mismatch for " + filename);
                  }

                  Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
                  Files.move(tmpSha1, targetSha1, REPLACE_EXISTING, ATOMIC_MOVE);

                  if (version.endsWith("-SNAPSHOT")) {
                    return Files.copy(
                        target,
                        localFile(repository, spec, artifactId + "-" + version + ".jar"),
                        REPLACE_EXISTING);
                  }

                  return target;
                } catch (Exception e) {
                  deleteIfExists(tmp);
                  deleteIfExists(tmpSha1);
                  throw new CompletionException(e);
                }
              });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Locates JAR in the local repository without hitting the network, or throwing error if artifact
   * cannot be found.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return expected {@link Path} to the local JAR
   */
  public Path resolveLocalJar(Repository repository, String spec) {
    final var jar = getLocalJar(repository, spec);

    if (!Files.exists(jar)) {
      throw new IllegalStateException(jar + " - not exist");
    }

    return jar;
  }

  /**
   * Locates JAR in the local repository without hitting the network.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return expected {@link Path} to the local JAR
   */
  public Path getLocalJar(Repository repository, String spec) {
    final var split = spec.split(":");

    if (split.length != 3) {
      throw new IllegalArgumentException("Wrong format: " + spec);
    }

    final var artifactId = split[1];
    final var version = split[2];

    return localFile(repository, spec, artifactId + "-" + version + ".jar");
  }

  private static String getVersion(Metadata metadata) {
    // Snapshot-level Metadata
    if (metadata.version() != null) {
      return metadata.version();
    }

    // GA-level Metadata
    if (metadata.versioning() != null) {
      if (metadata.versioning().release() != null) {
        return metadata.versioning().release();
      }
      if (metadata.versioning().latest() != null) {
        return metadata.versioning().latest();
      }
    }

    throw new IllegalArgumentException(
        "Cannot resolve version for artifactId=" + metadata.artifactId());
  }

  private static String getFilename(Metadata metadata, String artifactId, String version) {
    if (metadata.versioning() == null) {
      throw new IllegalArgumentException(
          "Cannot resolve filename for artifactId=" + metadata.artifactId());
    }
    if (metadata.versioning().snapshot() != null) {
      final var snapshot = metadata.versioning().snapshot();
      // Construct the timestamped name, e.g. 2.1.0-20260225.142030-45
      final var timestampedVersion =
          version.replace("-SNAPSHOT", "")
              + "-"
              + snapshot.timestamp()
              + "-"
              + snapshot.buildNumber();
      return artifactId + "-" + timestampedVersion + ".jar";
    } else {
      // Standard GA Release name
      return artifactId + "-" + version + ".jar";
    }
  }

  private static String expectedSha1(Path tmpSha1) {
    try {
      try (var reader = Files.newBufferedReader(tmpSha1)) {
        return reader.readLine().split("\\s+")[0].trim();
      }
    } catch (Exception e) {
      throw new CompletionException(e);
    }
  }

  private static String computeSha1(Path path) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-1");
      try (var is = Files.newInputStream(path)) {
        final var buffer = new byte[8192];
        int read;
        while ((read = is.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
    } catch (Exception e) {
      throw new CompletionException(e);
    }

    final var sb = new StringBuilder();
    for (byte b : digest.digest()) {
      sb.append(String.format("%02x", b));
    }

    return sb.toString();
  }

  private static void deleteIfExists(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // no-op
    }
  }
}
