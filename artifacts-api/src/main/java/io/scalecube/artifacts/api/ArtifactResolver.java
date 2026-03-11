package io.scalecube.artifacts.api;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Core service for locating and retrieving build artifacts.
 *
 * <p>This interface abstracts the complexity of repository layouts, checksum validation, and
 * version synchronization policies.
 */
public interface ArtifactResolver {

  /**
   * Resolves specified artifact coordinate to local file path.
   *
   * @param spec The artifact coordinate string (G:A:V)
   * @return future completing with the {@link Path} to the local JAR file
   */
  CompletableFuture<Path> resolve(String spec);
}
