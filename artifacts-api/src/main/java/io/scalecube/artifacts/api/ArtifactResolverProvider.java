package io.scalecube.artifacts.api;

import java.util.Properties;

/**
 * Factory interface for bootstrapping artifact resolution services.
 *
 * <p>Implementations are responsible for mapping flat configuration properties to fully initialized
 * resolver instances, including repository credentials and filesystem paths.
 */
public interface ArtifactResolverProvider {

  /**
   * Creates new instance of {@link ArtifactResolver} based on the provided configuration.
   *
   * @param props {@link Properties} object containing implementation-specific settings
   * @return configured {@link ArtifactResolver} instance
   */
  ArtifactResolver create(Properties props);
}
