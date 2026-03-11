package io.scalecube.artifacts.maven;

/**
 * Defines the strategy for checking artifact repositories updates. Determines whether the resolver
 * should attempt to synchronize with the remote repository or rely on the local file system.
 */
public enum UpdatePolicy {

  /** Always check remote metadata. If remote is newer, download. */
  REMOTE,

  /** Only use files where {@code localCopy: true} exists. Do not hit the network. */
  LOCAL
}
