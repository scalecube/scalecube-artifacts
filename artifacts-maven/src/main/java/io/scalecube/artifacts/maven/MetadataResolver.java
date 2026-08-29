package io.scalecube.artifacts.maven;

import static io.scalecube.artifacts.maven.Repository.localFile;
import static io.scalecube.artifacts.maven.Repository.remoteUri;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Component that resolves maven metadata (maven-metadata.xml) to determine the latest versions or
 * snapshot timestamps from both local and remote sources.
 */
public class MetadataResolver {

  private final Fetcher fetcher;

  public MetadataResolver(Fetcher fetcher) {
    this.fetcher = fetcher;
  }

  /**
   * Reads the existing local metadata for the given artifact spec.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return parsed {@link Metadata}, or null if no local metadata exists
   */
  public Metadata getCurrent(Repository repository, String spec) {
    final var target = localFile(repository, spec, "maven-metadata-" + repository.id() + ".xml");

    if (!Files.exists(target)) {
      return null;
    }

    try (final var in = new FileInputStream(target.toFile())) {
      return MetadataParser.parseMetadata(in);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse maven-metadata for " + spec, e);
    }
  }

  /**
   * Fetches the latest metadata from the remote repository.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return future completing with the remote {@link Metadata}
   */
  public CompletableFuture<Metadata> resolveRemote(Repository repository, String spec) {
    try {
      final var targetName = "maven-metadata-" + repository.id() + ".xml";
      final var target = localFile(repository, spec, targetName);
      final var targetSha1 = localFile(repository, spec, targetName + ".sha1");

      final var uri = remoteUri(repository, spec, "maven-metadata.xml");
      final var uriSha1 = remoteUri(repository, spec, "maven-metadata.xml.sha1");

      // thenCombine only runs when both succeed, so if one fails the other's downloaded temp file
      // would strand in the repository. Each future deletes its own temp when the combined result
      // fails.
      final var metadataFetch = fetcher.get(uri, repository.authz(), target.getParent());
      final var sha1Fetch = fetcher.get(uriSha1, repository.authz(), target.getParent());

      return metadataFetch
          .thenCombine(
              sha1Fetch,
              (tmp, tmpSha1) -> {
                try {
                  final var actualSha1 = computeSha1(tmp);
                  final var expectedSha1 = expectedSha1(tmpSha1);

                  if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                    throw new IOException("Checksum mismatch for maven-metadata");
                  }

                  // Parse before publishing, so a failure here never leaves a half-written file
                  // in place of a good one.
                  final Metadata metadata;
                  try (final var in = Files.newInputStream(tmp)) {
                    metadata = MetadataParser.parseMetadata(in);
                  }

                  publish(tmpSha1, targetSha1, computeSha1(tmpSha1));
                  publish(tmp, target, actualSha1);

                  return metadata;
                } catch (Exception e) {
                  deleteIfExists(tmp);
                  deleteIfExists(tmpSha1);
                  throw new CompletionException(e);
                }
              })
          .whenComplete(
              (metadata, ex) -> {
                if (ex != null) {
                  deleteFetched(metadataFetch);
                  deleteFetched(sha1Fetch);
                }
              });
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Publishes a downloaded file with an atomic move, treating "the identical content is already
   * there" as success. Metadata is fetched on every resolve, so concurrent resolutions of one
   * coordinate all publish the same bytes and only the first move is needed. On Windows replacing
   * a file another thread holds open fails, and overwriting identical content is not worth that.
   */
  private static void publish(Path tmp, Path target, String sha1) throws IOException {
    if (hasContent(target, sha1)) {
      deleteIfExists(tmp);
      return;
    }

    try {
      Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
    } catch (IOException e) {
      // Another publisher may have won the race between the check above and this move.
      if (hasContent(target, sha1)) {
        deleteIfExists(tmp);
        return;
      }
      throw e;
    }
  }

  private static boolean hasContent(Path path, String sha1) {
    try {
      if (!Files.isRegularFile(path) || Files.size(path) == 0) {
        return false;
      }
      return sha1.equalsIgnoreCase(computeSha1(path));
    } catch (Exception e) {
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

  /**
   * Deletes the temp file a fetch downloaded, once the resolution around it has failed. A fetch
   * that failed already cleaned up after itself; this is for the one that succeeded while the
   * other failed, whose file would otherwise be left behind in the repository.
   *
   * @param fetch a completed or failed fetch
   */
  private static void deleteFetched(CompletableFuture<Path> fetch) {
    if (fetch.isDone() && !fetch.isCompletedExceptionally() && !fetch.isCancelled()) {
      deleteIfExists(fetch.join());
    }
  }

  private static void deleteIfExists(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // no-op
    }
  }
}
