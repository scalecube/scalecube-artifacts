package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MavenResolverTest {

  @TempDir private Path tempCacheDir;
  @Mock private MetadataResolver metadataResolver;
  @Mock private JarResolver jarResolver;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
  }

  @Test
  void resolve_release_remotePolicy_downloadsWhenNoLocal() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.2.3";

    Metadata meta = new Metadata().groupId("com.foo").artifactId("bar").version("1.2.3");
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(meta));

    Path fakeJar = tempCacheDir.resolve("fake.jar");
    when(jarResolver.resolveJar(repository, meta))
        .thenReturn(CompletableFuture.completedFuture(fakeJar));

    Path result = mavenResolver.resolve(spec).join();

    assertEquals(fakeJar, result);
    verify(metadataResolver).resolveRemote(repository, spec);
    verify(jarResolver).resolveJar(repository, meta);
  }

  @Test
  void resolve_snapshot_remotePolicy_downloadsWhenMetadataChanged() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata localMeta =
        new Metadata().versioning(new Metadata.Versioning().lastUpdated("20231010120000"));

    Metadata remoteMeta =
        new Metadata()
            .version("1.0-SNAPSHOT")
            .versioning(
                new Metadata.Versioning()
                    .lastUpdated("20250309141500") // newer
                    .snapshot(
                        new Metadata.Snapshot().timestamp("20250309.141500").buildNumber("23")));

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(localMeta);
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(remoteMeta));

    Path newJar = tempCacheDir.resolve("bar-1.0-20250309.141500-23.jar");
    when(jarResolver.resolveJar(repository, remoteMeta))
        .thenReturn(CompletableFuture.completedFuture(newJar));

    Path result = mavenResolver.resolve(spec).join();

    assertEquals(newJar, result);
    verify(metadataResolver).resolveRemote(repository, spec);
    verify(jarResolver).resolveJar(repository, remoteMeta);
  }

  @Test
  void resolve_snapshot_remotePolicy_returnsLocalWhenMetadataUnchanged() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata sameMeta = createSnapshotMetadata("20231010120000", "20231010.120000", "5");

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(sameMeta);
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(sameMeta));

    Path localJar = tempCacheDir.resolve("bar-1.0-20231010.120000-5.jar");

    // Critical: make the file "exist" in the eyes of Files.exists()
    when(jarResolver.getLocalJar(repository, spec)).thenReturn(localJar);
    when(jarResolver.resolveLocalJar(repository, spec)).thenReturn(localJar);

    // Simulate that the JAR physically exists
    // Option A: actually create empty file (realistic)
    try {
      Files.createFile(localJar);
    } catch (IOException e) {
      fail("Cannot create test file", e);
    }

    // Act
    Path result = mavenResolver.resolve(spec).join();

    // Assert
    assertEquals(localJar, result);
    verify(metadataResolver).resolveRemote(repository, spec);
    verify(jarResolver, never()).resolveJar(any(), any()); // ← now passes
  }

  @Test
  void resolve_snapshot_remotePolicy_downloadsWhenLocalJarMissingEvenIfMetadataUnchanged() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata sameMeta =
        new Metadata()
            .version("1.0-SNAPSHOT")
            .versioning(new Metadata.Versioning().lastUpdated("20231010120000"));

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(sameMeta);
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(sameMeta));

    Path localJar = tempCacheDir.resolve("missing.jar");
    when(jarResolver.getLocalJar(repository, spec)).thenReturn(localJar);
    // Simulate file does NOT exist
    when(jarResolver.resolveLocalJar(repository, spec)).thenReturn(localJar);

    Path downloadedJar = tempCacheDir.resolve("downloaded.jar");
    when(jarResolver.resolveJar(repository, sameMeta))
        .thenReturn(CompletableFuture.completedFuture(downloadedJar));

    Path result = mavenResolver.resolve(spec).join();

    assertEquals(downloadedJar, result);
    verify(jarResolver).resolveJar(repository, sameMeta); // fallback download
  }

  @Test
  void resolve_localPolicy_returnsLocalJarForRelease() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.LOCAL);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.2.3";
    Path localJar = tempCacheDir.resolve("bar-1.2.3.jar");

    when(jarResolver.resolveLocalJar(repository, spec)).thenReturn(localJar);

    Path result = mavenResolver.resolve(spec).join();

    assertEquals(localJar, result);
    verifyNoInteractions(metadataResolver); // no remote call
  }

  @Test
  void resolve_snapshot_remotePolicy_metadataChanged_lastUpdatedNewer_downloadsNewJar() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata localMeta = createSnapshotMetadata("20231010120000", "20231010.120000", "5");
    Metadata remoteMeta =
        createSnapshotMetadata("20250309141500", "20250309.141500", "23"); // newer

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(localMeta);
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(remoteMeta));

    Path newJar = tempCacheDir.resolve("bar-1.0-20250309.141500-23.jar");
    when(jarResolver.resolveJar(repository, remoteMeta))
        .thenReturn(CompletableFuture.completedFuture(newJar));

    // Act
    Path result = mavenResolver.resolve(spec).join();

    // Assert
    assertEquals(newJar, result);
    verify(jarResolver).resolveJar(repository, remoteMeta); // download triggered
  }

  @Test
  void resolve_snapshot_remotePolicy_localMetadataMissing_assumesChanged_downloads() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata remoteMeta = createSnapshotMetadata("20250309141500", "20250309.141500", "23");

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(null); // no local metadata
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(remoteMeta));

    Path downloadedJar = tempCacheDir.resolve("bar-1.0-20250309.141500-23.jar");
    when(jarResolver.resolveJar(repository, remoteMeta))
        .thenReturn(CompletableFuture.completedFuture(downloadedJar));

    // Act
    Path result = mavenResolver.resolve(spec).join();

    // Assert
    assertEquals(downloadedJar, result);
    verify(jarResolver).resolveJar(repository, remoteMeta);
  }

  @Test
  void resolve_snapshot_remotePolicy_remoteMissingSnapshot_assumesChanged_downloads() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata localMeta = createSnapshotMetadata("20231010120000", "20231010.120000", "5");
    Metadata remoteMeta =
        new Metadata() // missing snapshot block
            .version("1.0-SNAPSHOT")
            .versioning(new Metadata.Versioning().lastUpdated("20250309141500"));

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(localMeta);
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(remoteMeta));

    Path downloadedJar = tempCacheDir.resolve("some.jar");
    when(jarResolver.resolveJar(repository, remoteMeta))
        .thenReturn(CompletableFuture.completedFuture(downloadedJar));

    // Act
    Path result = mavenResolver.resolve(spec).join();

    // Assert
    assertEquals(downloadedJar, result);
  }

  @Test
  void resolve_snapshot_remotePolicy_localMissingLastUpdated_assumesChanged_downloads() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata localMeta =
        new Metadata()
            .version("1.0-SNAPSHOT")
            .versioning(new Metadata.Versioning()); // no lastUpdated

    Metadata remoteMeta = createSnapshotMetadata("20250309141500", "20250309.141500", "23");

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(localMeta);
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(remoteMeta));

    Path newJar = tempCacheDir.resolve("bar-1.0-20250309.141500-23.jar");
    when(jarResolver.resolveJar(repository, remoteMeta))
        .thenReturn(CompletableFuture.completedFuture(newJar));

    // Act
    Path result = mavenResolver.resolve(spec).join();

    // Assert
    assertEquals(newJar, result);
  }

  @Test
  void resolve_snapshot_remotePolicy_localAndRemoteMissingLastUpdated_assumesChanged_downloads() {
    Repository repository =
        new Repository(
            "central",
            "http://localhost:0",
            "Bearer cool-token",
            tempCacheDir.toFile(),
            UpdatePolicy.REMOTE);

    MavenResolver mavenResolver = new MavenResolver(repository, metadataResolver, jarResolver);

    String spec = "com.foo:bar:1.0-SNAPSHOT";

    Metadata emptyMeta =
        new Metadata()
            .version("1.0-SNAPSHOT")
            .versioning(new Metadata.Versioning()); // no lastUpdated

    when(metadataResolver.getCurrent(repository, spec)).thenReturn(emptyMeta);
    when(metadataResolver.resolveRemote(repository, spec))
        .thenReturn(CompletableFuture.completedFuture(emptyMeta));

    Path downloadedJar = tempCacheDir.resolve("bar-1.0-some-timestamp.jar");
    when(jarResolver.resolveJar(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(downloadedJar));

    // Act
    Path result = mavenResolver.resolve(spec).join();

    // Assert
    verify(jarResolver).resolveJar(any(), any()); // fallback download
  }

  private Metadata createSnapshotMetadata(
      String lastUpdated, String timestamp, String buildNumber) {
    return new Metadata()
        .version("1.0-SNAPSHOT")
        .versioning(
            new Metadata.Versioning()
                .lastUpdated(lastUpdated)
                .snapshot(new Metadata.Snapshot().timestamp(timestamp).buildNumber(buildNumber)));
  }
}
