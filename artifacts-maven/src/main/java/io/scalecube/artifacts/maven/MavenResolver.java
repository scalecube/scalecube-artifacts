package io.scalecube.artifacts.maven;

import io.scalecube.artifacts.api.ArtifactResolver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for artifact resolution by GAV coordinates. Orchestrates maven metadata checks and
 * JAR downloads based on the provided {@link UpdatePolicy}.
 *
 * <p>The two policies do not overlap. {@link UpdatePolicy#REMOTE} always checks remote metadata and
 * downloads when the remote is newer; whether a locally installed build exists is irrelevant.
 * {@link UpdatePolicy#LOCAL} serves the locally installed build and never uses the network, and
 * throws when there is none.
 */
public class MavenResolver implements ArtifactResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(MavenResolver.class);

  private final Repository repository;
  private final MetadataResolver metadataResolver;
  private final JarResolver jarResolver;

  public MavenResolver(Repository repository) {
    this(
        repository,
        new MetadataResolver(newFetcher(repository)),
        new JarResolver(newFetcher(repository)));
  }

  private static Fetcher newFetcher(Repository repository) {
    return new Fetcher(
        Fetcher.newHttpClient(Duration.ofMillis(repository.connectTimeoutMs())),
        repository.retryMaxAttempts(),
        repository.retryInitialDelayMs(),
        repository.retryMaxDelayMs(),
        Duration.ofMillis(repository.requestTimeoutMs()));
  }

  public MavenResolver(
      Repository repository, MetadataResolver metadataResolver, JarResolver jarResolver) {
    this.repository = repository;
    this.metadataResolver = metadataResolver;
    this.jarResolver = jarResolver;
  }

  /**
   * Resolves JAR artifact to the local file path.
   *
   * @param spec artifact coordinate in "groupId:artifactId:version" format
   * @return future completing with the {@link Path} to the resolved JAR
   */
  @Override
  public CompletableFuture<Path> resolve(String spec) {
    final Coordinates coordinates;
    try {
      coordinates = Coordinates.parse(spec);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }

    try {
      if (repository.repoUpdatePolicy() == UpdatePolicy.LOCAL) {
        return CompletableFuture.completedFuture(jarResolver.resolveLocalJar(repository, spec));
      }
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }

    return doResolve(coordinates, 1);
  }

  private CompletableFuture<Path> doResolve(Coordinates coordinates, int attempt) {
    return jar(coordinates).exceptionallyCompose(ex -> retryNotReady(ex, coordinates, attempt));
  }

  private CompletableFuture<Path> jar(Coordinates coordinates) {
    return metadataResolver
        .resolveRemote(repository, coordinates.spec())
        .thenCompose(metadata -> jarResolver.resolveJar(repository, coordinates, metadata));
  }

  /**
   * Retries the whole chain while a publish is in flight, not just the jar. A freshly published
   * artifact takes time to appear in the registry, and its metadata answers 404 before its jar
   * does, so retrying only the jar never helped. A publish also uploads maven-metadata.xml and its
   * .sha1 separately, so a resolution that falls between the two sees a checksum mismatch; the
   * retry fetches both files again.
   */
  private CompletableFuture<Path> retryNotReady(
      Throwable ex, Coordinates coordinates, int attempt) {
    final Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
    final boolean notReady =
        cause instanceof FetchException fe && fe.statusCode() == 404
            || cause instanceof ChecksumMismatchException;
    if (notReady && attempt < repository.retryMaxAttempts()) {
      final long delayMs =
          Math.min(repository.retryInitialDelayMs() << (attempt - 1), repository.retryMaxDelayMs());
      LOGGER.info(
          "Not published yet: {} ({}), retrying in {}ms (attempt {}/{})",
          coordinates,
          cause.getMessage(),
          delayMs,
          attempt,
          repository.retryMaxAttempts());
      return CompletableFuture.runAsync(
              () -> {}, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
          .thenCompose(ignored -> doResolve(coordinates, attempt + 1));
    }
    return CompletableFuture.failedFuture(
        ex instanceof CompletionException ? ex : new CompletionException(ex));
  }
}
