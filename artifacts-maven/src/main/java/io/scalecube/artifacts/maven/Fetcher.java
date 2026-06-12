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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Component that responsible for transferring resources between remote repository and local system
 * via HTTP. Optimized for streaming large files directly to temporary disk locations.
 */
public class Fetcher {

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().version(Version.HTTP_2).followRedirects(Redirect.NORMAL).build();

  private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

  private static final int DEFAULT_MAX_ATTEMPTS = 10;
  private static final long DEFAULT_INITIAL_DELAY_MS = 3000L;
  private static final long MAX_DELAY_MS = 60_000L;

  private final HttpClient client;
  private final int maxAttempts;
  private final long initialDelayMs;

  public Fetcher() {
    this(HTTP_CLIENT, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY_MS);
  }

  public Fetcher(HttpClient client) {
    this(client, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY_MS);
  }

  public Fetcher(int maxAttempts, long initialDelayMs) {
    this(HTTP_CLIENT, maxAttempts, initialDelayMs);
  }

  public Fetcher(HttpClient client, int maxAttempts, long initialDelayMs) {
    this.client = client;
    this.maxAttempts = maxAttempts;
    this.initialDelayMs = initialDelayMs;
  }

  /**
   * Retrieves resource from the specified URI to temporary file in the target directory. Ensures
   * that temporary file is deleted if the download fails or the server returns an error. Retries on
   * network errors and retryable HTTP status codes (429, 502, 503, 504) with exponential backoff.
   *
   * @param uri remote location of the resource
   * @param authz authorization string for HTTP request
   * @param targetDir directory where the temporary file should be created.
   * @return future completing with the {@link Path} to the downloaded temporary file.
   */
  public CompletableFuture<Path> get(URI uri, String authz, Path targetDir) {
    return doGet(uri, authz, targetDir, 1);
  }

  private CompletableFuture<Path> doGet(URI uri, String authz, Path targetDir, int attempt) {
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
              if (ex != null || response.statusCode() != 200) {
                deleteIfExists(tmp);
                final boolean shouldRetry =
                    (ex != null || RETRYABLE_STATUSES.contains(response.statusCode()))
                        && attempt < maxAttempts;
                if (shouldRetry) {
                  final long delayMs = Math.min(initialDelayMs << (attempt - 1), MAX_DELAY_MS);
                  return CompletableFuture.runAsync(
                          () -> {},
                          CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                      .thenCompose(ignored -> doGet(uri, authz, targetDir, attempt + 1));
                }
                throw ex != null
                    ? new CompletionException(ex)
                    : new CompletionException("Fetch failed: " + response.statusCode(), null);
              }
              return CompletableFuture.completedFuture(tmp);
            })
        .thenCompose(f -> f);
  }

  private static void deleteIfExists(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // no-op
    }
  }
}
