package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
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
        new Repository(
            "central",
            "http://localhost:" + server.getAddress().getPort(),
            "Bearer cool-token",
            m2Repo.toFile(),
            UpdatePolicy.REMOTE);

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
            .resolveJar(repository, newMetadata("com.foo", "bar", "1.0", "20231010120000"))
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
                .resolveJar(repository, newMetadata("com.foo", "bar", "1.0", "20231010120000"))
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
  void shouldResolveSnapshotAndCreateAlias() throws Exception {
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
    Path result = jarResolver.resolveJar(repository, metadata).join();

    // Verify: -SNAPSHOT.jar alias was created
    Path alias = result.resolveSibling("bar-" + version + ".jar");
    assertTrue(Files.exists(alias), "Snapshot alias should be created");
    assertArrayEquals(jarContent, Files.readAllBytes(alias));

    // Verify: result is the timestamped jar
    Path timestamped = result.resolveSibling("bar-" + timestampedVersion + ".jar");
    assertTrue(Files.exists(timestamped), "Timestamped snapshot should be created");
    assertArrayEquals(jarContent, Files.readAllBytes(timestamped));
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
    jarResolver.resolveJar(repository, newMetadata("com.foo", "bar", "1.0", "2023")).join();

    // Verify
    assertArrayEquals(newContent, Files.readAllBytes(finalJar));
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
