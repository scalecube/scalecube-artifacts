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
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Component that manages local storage of JAR files within the maven repository structure. Handles
 * checksum verification and atomic file moves to ensure repository integrity.
 *
 * <h2>Why nothing here writes {@code <artifactId>-<version>.jar}</h2>
 *
 * <p>The base-named file is Maven's slot for a build produced by {@code mvn install}; a remote
 * SNAPSHOT lives under its timestamped name. Publishing a base-named alias for a downloaded build
 * broke two things at once:
 *
 * <ul>
 *   <li>it overwrote a developer's locally built jar, so a suite that started the service silently
 *       exercised the remote build instead;
 *   <li>the alias was published with a non-atomic {@code Files.copy(REPLACE_EXISTING)}, so two
 *       concurrent resolutions of the same coordinate raced on one path - one thread would see
 *       {@code FileAlreadyExistsException}, or worse, hand a half-written jar to a class loader.
 * </ul>
 *
 * <p>Returning the timestamped path removes both by construction: the name identifies the build, so
 * a present file is always the right file, and publishing it is a single atomic rename.
 */
public class JarResolver {

  private static final System.Logger LOGGER = System.getLogger(JarResolver.class.getName());

  private final Fetcher fetcher;

  public JarResolver(Fetcher fetcher) {
    this.fetcher = fetcher;
  }

  /**
   * Resolves the JAR described by {@code metadata}, serving it from the local repository when that
   * exact build is already cached and downloading it otherwise. Performs SHA-1 verification and
   * publishes the download with an atomic move.
   *
   * @param repository artifact repository
   * @param metadata artifact metadata
   * @return future completing with the final local {@link Path} of the JAR
   */
  public CompletableFuture<Path> resolveJar(Repository repository, Metadata metadata) {
    try {
      final var groupId = metadata.groupId();
      final var artifactId = metadata.artifactId();
      final var version = getVersion(metadata); // e.g., "1.2.3" or "2.1.0-SNAPSHOT"
      final var spec = String.join(":", groupId, artifactId, version);
      final var filename = getFilename(metadata, artifactId, version);

      final var target = localFile(repository, spec, filename);
      final var targetSha1 = localFile(repository, spec, filename + ".sha1");

      // The file name identifies the build - a timestamped SNAPSHOT or an immutable release - so a
      // cached copy needs no freshness check, only an integrity one. Anything we wrote has its
      // .sha1 beside it; a jar without one, or one that disagrees, is re-downloaded rather than
      // handed to a class loader.
      if (isVerifiedCache(target, targetSha1)) {
        LOGGER.log(Level.DEBUG, () -> "Serving " + spec + " from local repository: " + target);
        return CompletableFuture.completedFuture(target);
      }

      final var uri = remoteUri(repository, spec, filename);
      final var uriSha1 = remoteUri(repository, spec, filename + ".sha1");

      LOGGER.log(Level.INFO, () -> "Downloading " + spec + " from " + uri);

      final var jarFetch = fetcher.get(uri, repository.authz(), target.getParent());
      final var sha1Fetch = fetcher.get(uriSha1, repository.authz(), target.getParent());

      return jarFetch
          .thenCombine(
              sha1Fetch,
              (tmp, tmpSha1) -> {
                try {
                  final var actualSha1 = computeSha1(tmp);
                  final var expectedSha1 = expectedSha1(tmpSha1);

                  if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                    throw new IOException("Checksum mismatch for " + filename);
                  }

                  // Atomic, so a concurrent reader sees either the previous file or this one, never
                  // a partial write, and two concurrent publishers cannot collide.
                  Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
                  Files.move(tmpSha1, targetSha1, REPLACE_EXISTING, ATOMIC_MOVE);

                  LOGGER.log(Level.INFO, () -> "Downloaded " + spec + " to " + target);
                  return target;
                } catch (Exception e) {
                  deleteIfExists(tmp);
                  deleteIfExists(tmpSha1);
                  throw new CompletionException(e);
                }
              })
          .whenComplete((path, ex) -> cleanUpOnFailure(ex, jarFetch, sha1Fetch));
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
   * Locates the JAR in the local repository without hitting the network: the build installed by
   * {@code mvn install} if there is one, otherwise the newest build already downloaded.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return {@link Path} to the local JAR, or {@code null} when there is none
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

    return newestCachedBuild(repository, coordinates);
  }

  /**
   * Returns the locally installed build of {@code spec}, or {@code null}.
   *
   * <p>This is Maven's {@code localCopy} rule: a SNAPSHOT that {@code mvn install} wrote into the
   * local repository takes precedence over every remote build, which is what lets a developer build
   * a service and then run a suite against that build. Both signals must agree - the base-named jar
   * exists, and {@code maven-metadata-local.xml} marks the snapshot as a local copy - so a
   * base-named file left behind by an older release of this library is not mistaken for one.
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

  /** The newest already-downloaded build of a snapshot, by timestamped file name. */
  private static Path newestCachedBuild(Repository repository, Coordinates coordinates) {
    final var directory = Repository.localDir(repository, coordinates.spec());
    if (!Files.isDirectory(directory)) {
      return null;
    }

    final var prefix = coordinates.artifactId() + "-" + coordinates.baseVersion() + "-";
    try (final var files = Files.list(directory)) {
      return files
          .filter(
              file -> {
                final var name = file.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".jar");
              })
          .filter(JarResolver::isReadable)
          // Names embed a fixed-width yyyyMMdd.HHmmss timestamp, so lexicographic order is
          // chronological order.
          .max(Comparator.comparing(file -> file.getFileName().toString()))
          .orElse(null);
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Cannot list " + directory, e);
      return null;
    }
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
    final var snapshot = metadata.versioning() == null ? null : metadata.versioning().snapshot();

    if (snapshot != null && snapshot.timestamp() != null && snapshot.buildNumber() != null) {
      // Construct the timestamped name, e.g. 2.1.0-20260225.142030-45
      final var timestampedVersion =
          version.replace("-SNAPSHOT", "")
              + "-"
              + snapshot.timestamp()
              + "-"
              + snapshot.buildNumber();
      return artifactId + "-" + timestampedVersion + ".jar";
    }

    // Standard GA Release name
    return artifactId + "-" + version + ".jar";
  }

  /**
   * {@code thenCombine} never runs its function when one side fails, so the successful side's
   * temporary file is abandoned. Delete it by asking the futures themselves rather than by globbing
   * the directory: a glob would also hit the in-flight temporary files of a concurrent resolution
   * of the same artifact, which is precisely the kind of cross-talk this class exists to avoid.
   */
  private static void cleanUpOnFailure(
      Throwable ex, CompletableFuture<Path> jarFetch, CompletableFuture<Path> sha1Fetch) {
    if (ex == null) {
      return;
    }
    jarFetch.thenAccept(JarResolver::deleteIfExists);
    sha1Fetch.thenAccept(JarResolver::deleteIfExists);
  }

  private static boolean isReadable(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0;
    } catch (IOException e) {
      return false;
    }
  }

  /** Whether {@code jar} is present and matches the {@code .sha1} stored next to it. */
  private static boolean isVerifiedCache(Path jar, Path sha1) {
    if (!isReadable(jar) || !isReadable(sha1)) {
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
