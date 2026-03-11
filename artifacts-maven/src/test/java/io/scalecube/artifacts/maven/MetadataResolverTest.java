package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataResolverTest {

  @TempDir private Path m2Repo;
  private HttpServer server;
  private MetadataResolver metadataResolver;
  private Repository repository;

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

    metadataResolver = new MetadataResolver(new Fetcher(HttpClient.newHttpClient()));
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void shouldReadLocalMetadataSuccessfully() throws IOException {
    String spec = "com.foo:bar:1.0-SNAPSHOT";
    String filename = "maven-metadata-" + repository.id() + ".xml";
    Path localPath = Repository.localFile(repository, spec, filename);

    Files.createDirectories(localPath.getParent());
    Files.writeString(
        localPath,
        """
        <metadata>
          <groupId>com.foo</groupId>
          <artifactId>bar</artifactId>
          <versioning>
            <lastUpdated>20231010120000</lastUpdated>
          </versioning>
        </metadata>
        """);

    Metadata metadata = metadataResolver.getCurrent(repository, spec);

    assertNotNull(metadata);
    assertEquals("com.foo", metadata.groupId());
    assertEquals("20231010120000", metadata.versioning().lastUpdated());
  }

  @Test
  void shouldResolveRemoteMetadataSuccessfully() {
    String spec = "com.foo:bar:1.0-SNAPSHOT";
    String xml =
        """
        <metadata>
          <groupId>com.foo</groupId>
          <artifactId>bar</artifactId>
          <versioning>
            <lastUpdated>20240101100000</lastUpdated>
          </versioning>
        </metadata>
        """;

    mockRemoteMetadata("/com/foo/bar/1.0-SNAPSHOT/maven-metadata.xml", xml.getBytes());

    // Execute
    Metadata remote = metadataResolver.resolveRemote(repository, spec).join();

    // Verify
    assertEquals("20240101100000", remote.versioning().lastUpdated());
  }

  @Test
  void shouldHandleMissingRemoteMetadata() {
    server.createContext(
        "/com/missing/pkg/1.0-SNAPSHOT/maven-metadata.xml",
        ex -> {
          ex.sendResponseHeaders(404, -1);
          ex.close();
        });

    // Execute
    CompletableFuture<Metadata> future =
        metadataResolver.resolveRemote(repository, "com.missing:pkg:1.0-SNAPSHOT");

    // Verify
    assertThrows(CompletionException.class, future::join);
  }

  private void mockRemoteMetadata(String path, byte[] content) {
    // Mock the maven-metadata
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
}
