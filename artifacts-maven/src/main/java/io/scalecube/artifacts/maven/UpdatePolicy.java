package io.scalecube.artifacts.maven;

/**
 * Defines the strategy for checking artifact repositories updates. Determines whether the resolver
 * should attempt to synchronize with the remote repository or rely on the local file system.
 */
public enum UpdatePolicy {

  /**
   * Always check remote metadata, and download when the remote is newer. Whether a locally
   * installed build exists is irrelevant under this policy.
   */
  REMOTE,

  /**
   * Serve the build installed by {@code mvn install}, and never use the network. Throws when there
   * is no locally installed build: setting this policy is a statement that the artifact is present
   * locally, so recovering from the remote would defeat the point.
   */
  LOCAL
}
