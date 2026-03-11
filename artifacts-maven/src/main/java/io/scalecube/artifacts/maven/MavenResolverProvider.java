package io.scalecube.artifacts.maven;

import io.scalecube.artifacts.api.ArtifactResolver;
import io.scalecube.artifacts.api.ArtifactResolverProvider;
import java.util.Properties;

public class MavenResolverProvider implements ArtifactResolverProvider {

  @Override
  public ArtifactResolver create(Properties props) {
    return new MavenResolver(Repository.newInstance(props));
  }
}
