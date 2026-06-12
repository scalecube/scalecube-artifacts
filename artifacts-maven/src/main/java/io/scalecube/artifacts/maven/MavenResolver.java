package io.scalecube.artifacts.maven;

import io.scalecube.artifacts.api.ArtifactResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Entry point for artifact resolution by GAV coordinates. Orchestrates maven metadata checks and
 * JAR downloads based on the provided {@link UpdatePolicy}.
 */
public class MavenResolver implements ArtifactResolver {

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
    final var split = spec.split(":");

    if (split.length != 3) {
      throw new IllegalArgumentException("Wrong format: " + spec);
    }

    final var version = split[2];

    if (!version.endsWith("-SNAPSHOT")) {
      if (repository.repoUpdatePolicy() == UpdatePolicy.LOCAL) {
        return CompletableFuture.completedFuture(jarResolver.resolveLocalJar(repository, spec));
      } else {
        return doDownload(spec, 1);
      }
    } else {
      if (repository.repoUpdatePolicy() == UpdatePolicy.LOCAL) {
        return CompletableFuture.completedFuture(jarResolver.resolveLocalJar(repository, spec));
      } else {
        return doResolveSnapshot(spec, 1);
      }
    }
  }

  private CompletableFuture<Path> doDownload(String spec, int attempt) {
    return metadataResolver
        .resolveRemote(repository, spec)
        .thenCompose(
            metadata ->
                jarResolver
                    .resolveJar(repository, metadata)
                    .exceptionallyCompose(
                        ex -> retryOn404(ex, attempt, () -> doDownload(spec, attempt + 1))));
  }

  private CompletableFuture<Path> doResolveSnapshot(String spec, int attempt) {
    final var current = metadataResolver.getCurrent(repository, spec);
    return metadataResolver
        .resolveRemote(repository, spec)
        .thenCompose(
            metadata -> {
              if (isMetadataChanged(current, metadata)) {
                return jarResolver
                    .resolveJar(repository, metadata)
                    .exceptionallyCompose(
                        ex -> retryOn404(ex, attempt, () -> doResolveSnapshot(spec, attempt + 1)));
              } else {
                final var localJar = jarResolver.getLocalJar(repository, spec);
                if (!Files.exists(localJar)) {
                  return jarResolver
                      .resolveJar(repository, metadata)
                      .exceptionallyCompose(
                          ex ->
                              retryOn404(ex, attempt, () -> doResolveSnapshot(spec, attempt + 1)));
                } else {
                  return CompletableFuture.completedFuture(localJar);
                }
              }
            });
  }

  private CompletableFuture<Path> retryOn404(
      Throwable ex, int attempt, Supplier<CompletableFuture<Path>> next) {
    final Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
    if (cause instanceof FetchException fe
        && fe.statusCode() == 404
        && attempt < repository.retryMaxAttempts()) {
      final long delayMs =
          Math.min(repository.retryInitialDelayMs() << (attempt - 1), MAX_DELAY_MS);
      return CompletableFuture.runAsync(
              () -> {}, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
          .thenCompose(ignored -> next.get());
    }
    return CompletableFuture.failedFuture(
        ex instanceof CompletionException ? ex : new CompletionException(ex));
  }

  private static boolean isMetadataChanged(Metadata local, Metadata remote) {
    if (local == null
        || local.versioning() == null
        || remote == null
        || remote.versioning() == null) {
      return true;
    }

    final var localLastUpdated = local.versioning().lastUpdated();
    final var remoteLastUpdated = remote.versioning().lastUpdated();
    if (localLastUpdated == null || remoteLastUpdated == null) {
      return true;
    }

    return !localLastUpdated.equals(remoteLastUpdated);
  }
}
