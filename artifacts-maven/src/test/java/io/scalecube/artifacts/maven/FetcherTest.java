package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetcherTest {

  @TempDir Path tempDir;
  private HttpServer server;
  private URI serverUri;
  private Fetcher fetcher;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.start();
    serverUri = URI.create("http://localhost:" + server.getAddress().getPort() + "/test.jar");
    fetcher = new Fetcher(HttpClient.newHttpClient());
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void successfullyDownloadFileAndReturnPath() throws Exception {
    byte[] content = "fake-jar-data".getBytes();
    server.createContext(
        "/test.jar",
        ex -> {
          ex.sendResponseHeaders(200, content.length);
          ex.getResponseBody().write(content);
          ex.close();
        });

    Path result = fetcher.get(serverUri, "", tempDir).join();

    assertTrue(Files.exists(result), "File should exist");
    assertArrayEquals(content, Files.readAllBytes(result));
    assertTrue(result.startsWith(tempDir), "Should be in target directory");
    assertTrue(result.getFileName().toString().endsWith(".tmp"));
  }

  @Test
  void notFoundErrorShouldThrowAndCleanUp() {
    server.createContext(
        "/test.jar",
        ex -> {
          ex.sendResponseHeaders(404, -1);
          ex.close();
        });

    CompletableFuture<Path> future = fetcher.get(serverUri, "", tempDir);

    // Assert exception
    CompletionException ex = assertThrows(CompletionException.class, future::join);
    assertTrue(ex.getMessage().contains("404"));

    // Verify cleanup
    try (var stream = Files.list(tempDir)) {
      assertEquals(0, stream.count(), "Temporary file should have been deleted on 404");
    } catch (IOException e) {
      fail(e);
    }
  }

  @Test
  void serverErrorShouldThrowAndCleanUp() {
    server.createContext(
        "/test.jar",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });

    CompletionException ex =
        assertThrows(CompletionException.class, () -> fetcher.get(serverUri, "", tempDir).join());
    assertTrue(ex.getMessage().contains("500"));

    // Verify cleanup
    try (var stream = Files.list(tempDir)) {
      assertEquals(0, stream.count(), "Temporary file should have been deleted on 404");
    } catch (IOException e) {
      fail(e);
    }
  }

  @Test
  void serverConnectionFailure() {
    // Stop server to simulate connection refused
    server.stop(0);

    CompletableFuture<Path> future = fetcher.get(serverUri, "", tempDir);

    assertThrows(CompletionException.class, future::join);

    // Verify cleanup
    try (var stream = Files.list(tempDir)) {
      assertEquals(
          0, stream.count(), "Temporary file should have been deleted on connection failure");
    } catch (IOException e) {
      fail(e);
    }
  }

  @Test
  void serverCloseConnectionEarly() {
    server.createContext(
        "/artifact-1.0.jar",
        exchange -> {
          // promise a lot, but close without sending anything
          exchange.sendResponseHeaders(200, 100_000);
          exchange.close();
        });

    assertThrows(CompletionException.class, () -> fetcher.get(serverUri, "", tempDir).join());

    // Verify cleanup
    try (var stream = Files.list(tempDir)) {
      assertEquals(
          0, stream.count(), "Temporary file should have been deleted on connection failure");
    } catch (IOException e) {
      fail(e);
    }
  }
}
