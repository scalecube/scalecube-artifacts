package io.scalecube.artifacts.maven;

import io.scalecube.artifacts.api.ArtifactResolver;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for artifact resolution by GAV coordinates. Orchestrates maven metadata checks and
 * JAR downloads based on the provided {@link UpdatePolicy}.
 *
 * <h2>Resolution order</h2>
 *
 * <ol>
 *   <li>a locally installed SNAPSHOT - one that {@code mvn install} wrote, marked {@code
 *       <localCopy>true</localCopy>} in {@code maven-metadata-local.xml} - always wins. That is
 *       Maven's own rule, and it is what lets a developer build a service locally and then run a
 *       suite against that build;
 *   <li>otherwise, under {@link UpdatePolicy#REMOTE}, the build named by the repository's {@code
 *       maven-metadata.xml}, served from the local repository if that exact build is already cached
 *       and downloaded otherwise;
 *   <li>under {@link UpdatePolicy#LOCAL} the network is never touched: the installed build, else
 *       the newest one already downloaded, else a failure.
 * </ol>
 *
 * <p>Resolution never falls back to a stale cached build when the remote is reachable but the
 * artifact is missing. Silently running an old jar is far harder to diagnose than a failed
 * resolution.
 */
public class MavenResolver implements ArtifactResolver {

  private static final System.Logger LOGGER = System.getLogger(MavenResolver.class.getName());

  private static final long MAX_DELAY_MS = 60_000L;

  private final Repository repository;
  private final MetadataResolver metadataResolver;
  private final JarResolver jarResolver;

  public MavenResolver(Repository repository) {
    this(
        repository,
        new MetadataResolver(
            new Fetcher(repository.retryMaxAttempts(), repository.retryInitialDelayMs())),
        new JarResolver(
            new Fetcher(repository.retryMaxAttempts(), repository.retryInitialDelayMs())));
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

      // Maven's localCopy rule: what `mvn install` produced beats anything remote.
      final var installed = jarResolver.getInstalledJar(repository, spec);
      if (installed != null) {
        return CompletableFuture.completedFuture(installed);
      }
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }

    return doResolve(coordinates, 1);
  }

  private CompletableFuture<Path> doResolve(Coordinates coordinates, int attempt) {
    return jar(coordinates).exceptionallyCompose(ex -> retryOn404(ex, coordinates, attempt));
  }

  private CompletableFuture<Path> jar(Coordinates coordinates) {
    final var spec = coordinates.spec();

    // A release has no version-level maven-metadata.xml in a Maven repository - asking for one only
    // buys a 404 - and its file name is fully determined by the coordinate.
    if (!coordinates.snapshot()) {
      final var metadata =
          new Metadata()
              .groupId(coordinates.groupId())
              .artifactId(coordinates.artifactId())
              .version(coordinates.version());
      return jarResolver.resolveJar(repository, metadata);
    }

    return metadataResolver
        .resolveRemote(repository, spec)
        .thenCompose(metadata -> jarResolver.resolveJar(repository, metadata));
  }

  /**
   * A freshly published artifact propagates through the registry with a lag, during which both its
   * metadata and its jar answer {@code 404}. Retrying the whole chain - not just the jar - is what
   * makes that lag survivable, because the metadata is what 404s first.
   */
  private CompletableFuture<Path> retryOn404(Throwable ex, Coordinates coordinates, int attempt) {
    final Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
    if (cause instanceof FetchException fe
        && fe.statusCode() == 404
        && attempt < repository.retryMaxAttempts()) {
      final long delayMs =
          Math.min(repository.retryInitialDelayMs() << (attempt - 1), MAX_DELAY_MS);
      LOGGER.log(
          Level.INFO,
          () ->
              "Not published yet: "
                  + coordinates
                  + ", retrying in "
                  + delayMs
                  + "ms (attempt "
                  + attempt
                  + "/"
                  + repository.retryMaxAttempts()
                  + ")");
      return CompletableFuture.runAsync(
              () -> {}, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
          .thenCompose(ignored -> doResolve(coordinates, attempt + 1));
    }
    return CompletableFuture.failedFuture(
        ex instanceof CompletionException ? ex : new CompletionException(ex));
  }
}
