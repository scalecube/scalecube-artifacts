package io.scalecube.artifacts.maven;

import io.scalecube.artifacts.api.ArtifactResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point for artifact resolution by GAV coordinates. Orchestrates maven metadata checks and
 * JAR downloads based on the provided {@link UpdatePolicy}.
 */
public class MavenResolver implements ArtifactResolver {

  private final Repository repository;
  private final MetadataResolver metadataResolver;
  private final JarResolver jarResolver;

  public MavenResolver(Repository repository) {
    this(repository, new MetadataResolver(new Fetcher()), new JarResolver(new Fetcher()));
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
      // Non-SNAPSHOT (release) case
      if (repository.repoUpdatePolicy() == UpdatePolicy.LOCAL) {
        return CompletableFuture.completedFuture(jarResolver.resolveLocalJar(repository, spec));
      } else {
        // Download
        return metadataResolver
            .resolveRemote(repository, spec)
            .thenCompose(metadata -> jarResolver.resolveJar(repository, metadata));
      }
    } else {
      // SNAPSHOT case
      if (repository.repoUpdatePolicy() == UpdatePolicy.LOCAL) {
        return CompletableFuture.completedFuture(jarResolver.resolveLocalJar(repository, spec));
      } else {
        // REMOTE policy: compare metadata
        final var current = metadataResolver.getCurrent(repository, spec);
        return metadataResolver
            .resolveRemote(repository, spec)
            .thenCompose(
                metadata -> {
                  if (isMetadataChanged(current, metadata)) {
                    // Download new JAR
                    return jarResolver.resolveJar(repository, metadata);
                  } else {
                    // Return existing local JAR
                    final var localJar = jarResolver.getLocalJar(repository, spec);
                    if (!Files.exists(localJar)) {
                      // Metadata unchanged, but JAR missing
                      return jarResolver.resolveJar(repository, metadata);
                    } else {
                      return CompletableFuture.completedFuture(localJar);
                    }
                  }
                });
      }
    }
  }

  private static boolean isMetadataChanged(Metadata local, Metadata remote) {
    if (local == null
        || local.versioning() == null
        || remote == null
        || remote.versioning() == null) {
      // No local metadata or incomplete -> assume changed
      return true;
    }

    final var localLastUpdated = local.versioning().lastUpdated();
    final var remoteLastUpdated = remote.versioning().lastUpdated();
    if (localLastUpdated == null || remoteLastUpdated == null) {
      // Missing timestamps -> assume changed
      return true;
    }

    return !localLastUpdated.equals(remoteLastUpdated);
  }
}
