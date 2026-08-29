package io.scalecube.artifacts.maven;

import static io.scalecube.artifacts.maven.Repository.localFile;
import static io.scalecube.artifacts.maven.Repository.remoteUri;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Component that manages local storage of JAR files within the maven repository structure. Handles
 * checksum verification and atomic file moves to ensure repository integrity.
 *
 * <p>Nothing here writes the base-named {@code <artifactId>-<version>.jar}. That name belongs to
 * {@code mvn install}; a downloaded SNAPSHOT is stored under its timestamped name. Writing the base
 * name overwrote locally built jars, and it was written with a non-atomic copy that two concurrent
 * resolutions of the same coordinate raced on.
 */
public class JarResolver {

  private static final System.Logger LOGGER = System.getLogger(JarResolver.class.getName());

  private static final Pattern VERSION_TOKEN = Pattern.compile("[A-Za-z0-9._-]+");

  private final Fetcher fetcher;

  public JarResolver(Fetcher fetcher) {
    this.fetcher = fetcher;
  }

  /**
   * Resolves the JAR for the requested coordinates. Serves it from the local repository when that
   * exact build is already there and its checksum matches, downloads it otherwise, and publishes
   * the download with an atomic move.
   *
   * <p>The local path is built from the requested coordinates, never from the downloaded metadata.
   * Metadata only supplies the timestamp and build number of the snapshot build to fetch, and those
   * are checked to be plain tokens, so nothing in the remote response can choose where a file is
   * written.
   *
   * @param repository artifact repository
   * @param coordinates requested artifact coordinates
   * @param metadata artifact metadata
   * @return future completing with the final local {@link Path} of the JAR
   */
  public CompletableFuture<Path> resolveJar(
      Repository repository, Coordinates coordinates, Metadata metadata) {
    try {
      final var spec = coordinates.spec();
      final var filename = coordinates.fileName(resolvedVersion(coordinates, metadata));

      final var target = localFile(repository, spec, filename);
      final var targetSha1 = localFile(repository, spec, filename + ".sha1");

      // The file name identifies the build, so a cached copy needs no freshness check, only a
      // checksum check. A jar with no .sha1 beside it, or one that disagrees, is downloaded again.
      if (isUsableCache(repository, target, targetSha1)) {
        return CompletableFuture.completedFuture(target);
      }

      final var uri = remoteUri(repository, spec, filename);
      final var uriSha1 = remoteUri(repository, spec, filename + ".sha1");

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

                  // Atomic: a concurrent reader sees the old file or the new one, never a partial
                  // write, and two concurrent publishers cannot collide.
                  Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
                  Files.move(tmpSha1, targetSha1, REPLACE_EXISTING, ATOMIC_MOVE);

                  LOGGER.log(Level.INFO, () -> "Downloaded " + spec + " to " + target);
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
   * Locates the JAR in the local repository without hitting the network, throwing if there is
   * nothing to serve.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return {@link Path} to the local JAR
   */
  public Path resolveLocalJar(Repository repository, String spec) {
    final var jar = getLocalJar(repository, spec);

    if (jar == null) {
      throw new IllegalStateException("No local copy of " + spec + " in " + repository.repoDir());
    }

    return jar;
  }

  /**
   * Locates the build installed by {@code mvn install}, without hitting the network. A build that
   * was merely downloaded earlier does not count: under {@link UpdatePolicy#LOCAL} the caller asked
   * for their own build, and serving some older download instead would hide its absence.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return {@link Path} to the locally installed JAR, or {@code null} when there is none
   */
  public Path getLocalJar(Repository repository, String spec) {
    final var installed = getInstalledJar(repository, spec);
    if (installed != null) {
      return installed;
    }

    final var coordinates = Coordinates.parse(spec);
    if (!coordinates.snapshot()) {
      final var jar = localFile(repository, spec, coordinates.fileName(coordinates.version()));
      return isReadable(jar) ? jar : null;
    }

    return null;
  }

  /**
   * Returns the build that {@code mvn install} put in the local repository, or null. Maven gives it
   * precedence over any remote build. Both signals must agree - the base-named jar is there and
   * {@code maven-metadata-local.xml} marks the snapshot as a local copy - so a base-named file left
   * by an older release of this library is not taken for one.
   */
  public Path getInstalledJar(Repository repository, String spec) {
    final var coordinates = Coordinates.parse(spec);
    if (!coordinates.snapshot()) {
      return null;
    }

    final var jar = localFile(repository, spec, coordinates.fileName(coordinates.version()));
    if (!isReadable(jar) || !isLocalCopy(repository, spec)) {
      return null;
    }

    LOGGER.log(Level.INFO, () -> "Serving locally installed " + spec + ": " + jar);
    return jar;
  }

  private static boolean isLocalCopy(Repository repository, String spec) {
    final var localMetadata = localFile(repository, spec, "maven-metadata-local.xml");
    if (!Files.isRegularFile(localMetadata)) {
      return false;
    }
    try (final var in = Files.newInputStream(localMetadata)) {
      final var metadata = MetadataParser.parseMetadata(in);
      return metadata.versioning() != null
          && metadata.versioning().snapshot() != null
          && metadata.versioning().snapshot().localCopy();
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Cannot read " + localMetadata, e);
      return false;
    }
  }

  /**
   * Returns the version to fetch: the timestamped build named by the snapshot metadata, or the
   * requested version when the metadata names no snapshot build. The base version always comes from
   * the requested coordinates, so only the timestamp and build number come off the wire.
   */
  private static String resolvedVersion(Coordinates coordinates, Metadata metadata) {
    final var snapshot = metadata.versioning() == null ? null : metadata.versioning().snapshot();

    if (coordinates.snapshot()
        && snapshot != null
        && snapshot.timestamp() != null
        && snapshot.buildNumber() != null) {
      // e.g. 2.1.0-20260225.142030-45
      return coordinates.baseVersion()
          + "-"
          + requireToken(snapshot.timestamp(), "timestamp")
          + "-"
          + requireToken(snapshot.buildNumber(), "buildNumber");
    }

    return coordinates.version();
  }

  /**
   * Rejects anything that is not a plain version token. A path separator or a {@code ..} segment
   * here would escape the artifact directory, so a malformed or hostile metadata response cannot
   * choose the file that gets written.
   */
  private static String requireToken(String value, String name) {
    if (!VERSION_TOKEN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid " + name + " in metadata: " + value);
    }
    return value;
  }

  private static boolean isReadable(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Whether the cached jar can be served. With {@link Repository#verifyCachedChecksum()} on, which
   * is the default, it must also match the {@code .sha1} stored next to it; checking costs one pass
   * over the file, so it can be turned off for large artifacts.
   */
  private static boolean isUsableCache(Repository repository, Path jar, Path sha1) {
    if (!isReadable(jar)) {
      return false;
    }
    if (!repository.verifyCachedChecksum()) {
      return true;
    }
    if (!isReadable(sha1)) {
      return false;
    }
    try {
      final var expected = expectedSha1(sha1);
      final var actual = computeSha1(jar);
      if (expected.equalsIgnoreCase(actual)) {
        return true;
      }
      LOGGER.log(Level.WARNING, () -> "Cached " + jar + " fails its checksum, re-downloading");
      return false;
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Cannot verify cached " + jar + ", re-downloading", e);
      return false;
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
