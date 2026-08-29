package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.security.MessageDigest;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarResolverTest {

  @TempDir private Path m2Repo;
  private HttpServer server;
  private Repository repository;
  private JarResolver jarResolver;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.start();

    repository =
        new Repository()
            .id("central")
            .url("http://localhost:" + server.getAddress().getPort())
            .authz("Bearer cool-token")
            .repoDir(m2Repo.toFile())
            .repoUpdatePolicy(UpdatePolicy.REMOTE);

    jarResolver = new JarResolver(new Fetcher(HttpClient.newHttpClient()));
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void shouldResolveSuccessfully() throws Exception {
    byte[] jarContent = "real-jar-bytes".getBytes();
    mockRemoteJar("/com/foo/bar/1.0/bar-1.0.jar", jarContent);

    // Execute
    Path result =
        jarResolver
            .resolveJar(
                repository,
                Coordinates.parse("com.foo:bar:1.0"),
                newMetadata("com.foo", "bar", "1.0", "20231010120000"))
            .join();

    // Verify
    assertTrue(Files.exists(result), "JAR should be in .m2");
    assertArrayEquals(jarContent, Files.readAllBytes(result));

    Path shaFile = result.resolveSibling(result.getFileName() + ".sha1");
    assertTrue(Files.exists(shaFile), "SHA1 should be in .m2");
    assertEquals(computeSha1(jarContent), Files.readString(shaFile).trim());
  }

  @Test
  void shouldRollbackAndCleanUpOnFailure() throws Exception {
    server.createContext(
        "/com/foo/bar/1.0/bar-1.0.jar",
        ex -> {
          ex.sendResponseHeaders(200, 11);
          ex.getResponseBody().write("jar-content".getBytes());
          ex.close();
        });
    server.createContext(
        "/com/foo/bar/1.0/bar-1.0.jar.sha1",
        ex -> {
          ex.sendResponseHeaders(200, 5);
          ex.getResponseBody().write("wrong".getBytes());
          ex.close();
        });

    // Execute
    assertThrows(
        CompletionException.class,
        () ->
            jarResolver
                .resolveJar(
                repository,
                Coordinates.parse("com.foo:bar:1.0"),
                newMetadata("com.foo", "bar", "1.0", "20231010120000"))
                .join());

    // Verify
    Path expectedDir = m2Repo.resolve("com/foo/bar/1.0");
    if (Files.exists(expectedDir)) {
      final var list = Files.list(expectedDir).toList();
      assertEquals(
          0, list.size(), "Directory should be empty after checksum failure, list: " + list);
    }
  }

  @Test
  void shouldResolveSnapshotToTimestampedFileWithoutAlias() throws Exception {
    String timestamp = "20231010.120000";
    String build = "1";
    String version = "1.0-SNAPSHOT";
    String timestampedVersion = "1.0-" + timestamp + "-" + build;

    byte[] jarContent = "snapshot-bytes".getBytes();
    mockRemoteJar("/com/foo/bar/" + version + "/bar-" + timestampedVersion + ".jar", jarContent);

    Metadata metadata =
        newMetadata("com.foo", "bar", version, "20231010120000")
            .versioning(
                new Metadata.Versioning()
                    .snapshot(new Metadata.Snapshot().timestamp(timestamp).buildNumber(build)));

    // Execute
    Path result =
        jarResolver
            .resolveJar(
                repository, Coordinates.parse("com.foo:bar:" + version), metadata)
            .join();

    // Verify: base-named file is Maven's slot for `mvn install`, so resolution must not write it
    Path alias = result.resolveSibling("bar-" + version + ".jar");
    assertFalse(Files.exists(alias), "Resolution must not write a -SNAPSHOT.jar alias");

    // Verify: result is the timestamped jar
    Path timestamped = result.resolveSibling("bar-" + timestampedVersion + ".jar");
    assertTrue(Files.exists(timestamped), "Timestamped snapshot should be created");
    assertArrayEquals(jarContent, Files.readAllBytes(timestamped));
  }

  @Test
  void shouldResolveWithDottedAndDashedGroupIdAndArtifactId() throws Exception {
    byte[] jarContent = "real-jar-bytes".getBytes();
    mockRemoteJar(
        "/io/scalecube/my-group/scalecube-my-artifact/1.0/scalecube-my-artifact-1.0.jar",
        jarContent);

    Path result =
        jarResolver
            .resolveJar(
                repository,
                Coordinates.parse("io.scalecube.my-group:scalecube-my-artifact:1.0"),
                newMetadata(
                    "io.scalecube.my-group", "scalecube-my-artifact", "1.0", "20231010120000"))
            .join();

    assertTrue(Files.exists(result), "JAR should be in .m2");
    assertArrayEquals(jarContent, Files.readAllBytes(result));
    assertTrue(
        result.startsWith(m2Repo.resolve("io/scalecube/my-group")),
        "groupId dots must become directories, got: " + result);
  }

  @Test
  void shouldResolveSnapshotWithDottedAndDashedGroupIdAndArtifactId() throws Exception {
    String timestamp = "20231010.120000";
    String build = "3";
    String version = "2.1.0-SNAPSHOT";
    String timestampedVersion = "2.1.0-" + timestamp + "-" + build;

    byte[] jarContent = "snapshot-bytes".getBytes();
    mockRemoteJar(
        "/io/scalecube/my-group/scalecube-my-artifact/"
            + version
            + "/scalecube-my-artifact-"
            + timestampedVersion
            + ".jar",
        jarContent);

    Metadata metadata =
        newMetadata("io.scalecube.my-group", "scalecube-my-artifact", version, "20231010120000")
            .versioning(
                new Metadata.Versioning()
                    .snapshot(new Metadata.Snapshot().timestamp(timestamp).buildNumber(build)));

    Path result =
        jarResolver
            .resolveJar(
                repository,
                Coordinates.parse("io.scalecube.my-group:scalecube-my-artifact:" + version),
                metadata)
            .join();

    Path alias = result.resolveSibling("scalecube-my-artifact-" + version + ".jar");
    assertFalse(Files.exists(alias), "Resolution must not write a -SNAPSHOT.jar alias");

    Path timestamped =
        result.resolveSibling("scalecube-my-artifact-" + timestampedVersion + ".jar");
    assertTrue(Files.exists(timestamped), "Timestamped snapshot should exist");
    assertArrayEquals(jarContent, Files.readAllBytes(timestamped));

    assertTrue(
        result.startsWith(m2Repo.resolve("io/scalecube/my-group")),
        "groupId dots must become directories, got: " + result);
  }

  @Test
  void shouldOverwriteExistingFileSuccessfully() throws Exception {
    // Pre-create "corrupt" file in the destination
    Path targetDir = m2Repo.resolve("com/foo/bar/1.0");
    Files.createDirectories(targetDir);
    Path finalJar = targetDir.resolve("bar-1.0.jar");
    Files.writeString(finalJar, "old-garbage-data");

    // Setup "new jar"
    byte[] newContent = "new-shiny-jar".getBytes();
    mockRemoteJar("/com/foo/bar/1.0/bar-1.0.jar", newContent);

    // Execute
    jarResolver
        .resolveJar(
            repository,
            Coordinates.parse("com.foo:bar:1.0"),
            newMetadata("com.foo", "bar", "1.0", "2023"))
        .join();

    // Verify
    assertArrayEquals(newContent, Files.readAllBytes(finalJar));
  }

  @Test
  void shouldServeCachedJarWithoutDownloadWhenChecksumMatches() throws Exception {
    byte[] cached = "already-here".getBytes();
    Path dir = m2Repo.resolve("com/foo/bar/1.0");
    Files.createDirectories(dir);
    Files.write(dir.resolve("bar-1.0.jar"), cached);
    Files.writeString(dir.resolve("bar-1.0.jar.sha1"), computeSha1(cached));

    // No remote context registered: any download attempt would fail the request.
    Path result =
        jarResolver
            .resolveJar(
                repository,
                Coordinates.parse("com.foo:bar:1.0"),
                newMetadata("com.foo", "bar", "1.0", "20231010120000"))
            .join();

    assertEquals(dir.resolve("bar-1.0.jar"), result);
    assertArrayEquals(cached, Files.readAllBytes(result));
  }

  @Test
  void shouldServeCachedJarWithoutChecksumWhenVerificationIsOff() throws Exception {
    Repository noVerify =
        new Repository()
            .id("central")
            .url(repository.url())
            .authz(repository.authz())
            .repoDir(m2Repo.toFile())
            .repoUpdatePolicy(UpdatePolicy.REMOTE)
            .retryMaxAttempts(Repository.DEFAULT_REPO_RETRY_MAX_ATTEMPTS)
            .retryInitialDelayMs(Repository.DEFAULT_REPO_RETRY_INITIAL_DELAY_MS)
            .verifyCachedChecksum(false);

    byte[] cached = "already-here".getBytes();
    Path dir = m2Repo.resolve("com/foo/bar/1.0");
    Files.createDirectories(dir);
    Files.write(dir.resolve("bar-1.0.jar"), cached);

    // No .sha1 beside it and no remote context: only the disabled check lets this succeed.
    Path result =
        jarResolver
            .resolveJar(
                noVerify,
                Coordinates.parse("com.foo:bar:1.0"),
                newMetadata("com.foo", "bar", "1.0", "20231010120000"))
            .join();

    assertArrayEquals(cached, Files.readAllBytes(result));
  }

  @Test
  void localPolicyServesInstalledBuild() throws Exception {
    final var dir = m2Repo.resolve("com/foo/bar/1.0-SNAPSHOT");
    Files.createDirectories(dir);
    final var installed = dir.resolve("bar-1.0-SNAPSHOT.jar");
    Files.write(installed, "installed".getBytes());
    Files.writeString(
        dir.resolve("maven-metadata-local.xml"),
        """
        <metadata>
          <versioning>
            <snapshot><localCopy>true</localCopy></snapshot>
          </versioning>
        </metadata>
        """);

    assertEquals(
        installed,
        jarResolver.resolveLocalJar(repository, "com.foo:bar:1.0-SNAPSHOT"),
        "installed build");
  }

  @Test
  void localPolicyThrowsWhenOnlyADownloadedBuildIsCached() throws Exception {
    // a timestamped build downloaded earlier is NOT a local build: LOCAL must throw, not serve it
    final var dir = m2Repo.resolve("com/foo/bar/1.0-SNAPSHOT");
    Files.createDirectories(dir);
    Files.write(dir.resolve("bar-1.0-20260225.142030-45.jar"), "downloaded".getBytes());

    assertThrows(
        IllegalStateException.class,
        () -> jarResolver.resolveLocalJar(repository, "com.foo:bar:1.0-SNAPSHOT"));
  }

  @Test
  void localPolicyThrowsWhenBaseNamedJarHasNoLocalCopyMarker() throws Exception {
    // a base-named jar left by an older release of this library must not pass as `mvn install`
    final var dir = m2Repo.resolve("com/foo/bar/1.0-SNAPSHOT");
    Files.createDirectories(dir);
    Files.write(dir.resolve("bar-1.0-SNAPSHOT.jar"), "stale-alias".getBytes());

    assertThrows(
        IllegalStateException.class,
        () -> jarResolver.resolveLocalJar(repository, "com.foo:bar:1.0-SNAPSHOT"));
  }

  @Test
  void hostileMetadataCannotChooseTheWritePath() throws Exception {
    byte[] jarContent = "payload".getBytes();
    mockRemoteJar("/com/foo/bar/1.0-SNAPSHOT/bar-1.0-20231010.120000-1.jar", jarContent);

    // The remote answers with coordinates that are not the ones we asked for. The reviewer's case:
    // it used to write to repoDir/com/1.0-SNAPSHOT/ instead of repoDir/com/foo/bar/1.0-SNAPSHOT/.
    Metadata hostile =
        newMetadata("com", "1.0-SNAPSHOT", "bar", "20231010120000")
            .versioning(
                new Metadata.Versioning()
                    .snapshot(
                        new Metadata.Snapshot().timestamp("20231010.120000").buildNumber("1")));

    Path result =
        jarResolver
            .resolveJar(repository, Coordinates.parse("com.foo:bar:1.0-SNAPSHOT"), hostile)
            .join();

    assertEquals(
        m2Repo.resolve("com/foo/bar/1.0-SNAPSHOT").normalize(),
        result.getParent().normalize(),
        "path must follow the requested coordinates, not the metadata");
    assertArrayEquals(jarContent, Files.readAllBytes(result));
  }

  @Test
  void traversalInMetadataTimestampIsRejected() {
    Metadata hostile =
        newMetadata("com.foo", "bar", "1.0-SNAPSHOT", "20231010120000")
            .versioning(
                new Metadata.Versioning()
                    .snapshot(
                        new Metadata.Snapshot()
                            .timestamp("../../../../etc/evil")
                            .buildNumber("1")));

    final var ex =
        assertThrows(
            CompletionException.class,
            () ->
                jarResolver
                    .resolveJar(repository, Coordinates.parse("com.foo:bar:1.0-SNAPSHOT"), hostile)
                    .join());

    assertInstanceOf(IllegalArgumentException.class, ex.getCause(), "cause");
  }

  @Test
  void traversalInBuildNumberIsRejected() {
    Metadata hostile =
        newMetadata("com.foo", "bar", "1.0-SNAPSHOT", "20231010120000")
            .versioning(
                new Metadata.Versioning()
                    .snapshot(
                        new Metadata.Snapshot()
                            .timestamp("20231010.120000")
                            .buildNumber("1/../../../evil")));

    final var ex =
        assertThrows(
            CompletionException.class,
            () ->
                jarResolver
                    .resolveJar(repository, Coordinates.parse("com.foo:bar:1.0-SNAPSHOT"), hostile)
                    .join());

    assertInstanceOf(IllegalArgumentException.class, ex.getCause(), "cause");
  }

  @Test
  void snapshotWithNoBuildInMetadataThrowsInsteadOfWritingTheBaseName() throws Exception {
    final var dir = m2Repo.resolve("com/foo/bar/1.0-SNAPSHOT");
    Files.createDirectories(dir);
    final var installed = dir.resolve("bar-1.0-SNAPSHOT.jar");
    Files.write(installed, "locally-built".getBytes());

    // The remote will happily serve a base-named jar with different content, so if resolution ever
    // asks for that name it overwrites what `mvn install` produced.
    mockRemoteJar("/com/foo/bar/1.0-SNAPSHOT/bar-1.0-SNAPSHOT.jar", "from-remote".getBytes());

    // Metadata for a snapshot that names no build. Falling back to the requested version would
    // resolve to bar-1.0-SNAPSHOT.jar, the slot `mvn install` owns.
    final var noBuild = newMetadata("com.foo", "bar", "1.0-SNAPSHOT", "20231010120000");

    final var ex =
        assertThrows(
            CompletionException.class,
            () ->
                jarResolver
                    .resolveJar(repository, Coordinates.parse("com.foo:bar:1.0-SNAPSHOT"), noBuild)
                    .join());

    assertInstanceOf(IllegalStateException.class, ex.getCause(), "cause");
    assertArrayEquals(
        "locally-built".getBytes(),
        Files.readAllBytes(installed),
        "the locally installed jar must be untouched");
  }

  @Test
  void temporaryFileIsNotLeftBehindWhenOnlyOneFetchSucceeds() throws Exception {
    // The jar is served but its .sha1 is not, so the combining step never runs. The jar's temp
    // file must still be cleaned up rather than stranded in the repository.
    final var jarContent = "downloaded".getBytes();
    server.createContext(
        "/com/foo/bar/1.0/bar-1.0.jar",
        ex -> {
          ex.sendResponseHeaders(200, jarContent.length);
          ex.getResponseBody().write(jarContent);
          ex.close();
        });
    // no context for the .sha1: it answers 404, so the combining step never runs
    server.createContext(
        "/com/foo/bar/1.0/bar-1.0.jar.sha1",
        ex -> {
          ex.sendResponseHeaders(404, -1);
          ex.close();
        });

    assertThrows(
        CompletionException.class,
        () ->
            jarResolver
                .resolveJar(
                    repository,
                    Coordinates.parse("com.foo:bar:1.0"),
                    newMetadata("com.foo", "bar", "1.0", "2023"))
                .join());

    final var dir = m2Repo.resolve("com/foo/bar/1.0");
    if (Files.isDirectory(dir)) {
      try (final var files = Files.list(dir)) {
        final var leftovers = files.filter(f -> f.toString().endsWith(".tmp")).toList();
        assertEquals(List.of(), leftovers, "no temp files may be left behind");
      }
    }
  }

  private void mockRemoteJar(String path, byte[] content) {
    // Mock the JAR
    server.createContext(
        path,
        ex -> {
          ex.sendResponseHeaders(200, content.length);
          ex.getResponseBody().write(content);
          ex.close();
        });

    // Mock the SHA1
    String sha1 = computeSha1(content);
    server.createContext(
        path + ".sha1",
        ex -> {
          byte[] shaBytes = sha1.getBytes();
          ex.sendResponseHeaders(200, shaBytes.length);
          ex.getResponseBody().write(shaBytes);
          ex.close();
        });
  }

  private static String computeSha1(byte[] bytes) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-1");
      digest.update(bytes);
    } catch (Exception e) {
      throw new CompletionException(e);
    }

    final var sb = new StringBuilder();
    for (byte b : digest.digest()) {
      sb.append(String.format("%02x", b));
    }

    return sb.toString();
  }

  private Metadata newMetadata(
      String groupId, String artifactId, String version, String lastUpdated) {
    return new Metadata()
        .groupId(groupId)
        .artifactId(artifactId)
        .version(version)
        .versioning(new Metadata.Versioning().lastUpdated(lastUpdated));
  }
}
