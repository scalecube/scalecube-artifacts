package io.scalecube.artifacts.maven;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Component that responsible for transferring resources between remote repository and local system
 * via HTTP. Optimized for streaming large files directly to temporary disk locations.
 */
public class Fetcher {

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().version(Version.HTTP_2).followRedirects(Redirect.NORMAL).build();

  private final HttpClient client;

  public Fetcher() {
    this(HTTP_CLIENT);
  }

  public Fetcher(HttpClient client) {
    this.client = client;
  }

  /**
   * Retrieves resource from the specified URI to temporary file in the target directory. Ensures
   * that temporary file is deleted if the download fails or the server returns an error.
   *
   * @param uri remote location of the resource
   * @param authz authorization string for HTTP request
   * @param targetDir directory where the temporary file should be created.
   * @return future completing with the {@link Path} to the downloaded temporary file.
   */
  public CompletableFuture<Path> get(URI uri, String authz, Path targetDir) {
    final Path tmp;
    try {
      Files.createDirectories(targetDir);
      final var name = Paths.get(uri.getPath()).getFileName().toString();
      tmp = Files.createTempFile(targetDir, name + "-", ".tmp");
    } catch (IOException e) {
      return CompletableFuture.failedFuture(e);
    }

    return client
        .sendAsync(
            HttpRequest.newBuilder(uri)
                .header("Authorization", authz != null ? authz : "")
                .GET()
                .build(),
            BodyHandlers.ofFile(tmp, CREATE, WRITE, TRUNCATE_EXISTING))
        .handle(
            (response, ex) -> {
              // Check for network errors or non-200 status codes
              if (ex != null || response.statusCode() != 200) {
                deleteIfExists(tmp);
                throw ex != null
                    ? new CompletionException(ex)
                    : new CompletionException("Fetch failed: " + response.statusCode(), null);
              }
              return tmp;
            });
  }

  private static void deleteIfExists(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // no-op
    }
  }
}
