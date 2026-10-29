package io.scalecube.artifacts.maven;

import java.io.IOException;

/**
 * Thrown when a downloaded file does not match its published SHA-1. A repository uploads a file and
 * its checksum separately, so a download that lands between the two uploads sees a mismatch that
 * clears once the publish completes.
 */
public class ChecksumMismatchException extends IOException {

  public ChecksumMismatchException(String filename) {
    super("Checksum mismatch for " + filename);
  }
}
