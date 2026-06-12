package io.scalecube.artifacts.maven;

/** Thrown when an HTTP request completes with a non-200 status code. */
public class FetchException extends RuntimeException {

  private final int statusCode;

  public FetchException(int statusCode) {
    super("Fetch failed: " + statusCode);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
