package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproduces the two defects caused by publishing a base-named {@code <artifactId>-<version>.jar}
 * alias next to the timestamped SNAPSHOT build.
 *
 * <p>Both are the failure mode observed in the exchange's integration suites: {@code
 * StartableService} hands the resolved path to a {@link java.net.URLClassLoader}, so a jar that is
 * only partially written, or one that is not the jar the developer built, surfaces much later as a
 * confusing Micronaut {@code NoSuchBeanException} rather than as a resolution error.
 */
class SnapshotAliasRegressionTest {

  private static final String GROUP_ID = "com.foo";
  private static final String ARTIFACT_ID = "bar";
  private static final String VERSION = "1.0-SNAPSHOT";
  private static final String SPEC = GROUP_ID + ":" + ARTIFACT_ID + ":" + VERSION;
  private static final String TIMESTAMP = "20260821.131034";
  private static final String BUILD_NUMBER = "175";
  private static final String TIMESTAMPED_VERSION = "1.0-" + TIMESTAMP + "-" + BUILD_NUMBER;
  private static final String DIR = "/com/foo/bar/1.0-SNAPSHOT";

  /** Big enough that the non-atomic alias copy takes long enough to lose the race. */
  private static final int ENTRY_COUNT = 400;

  private static final int ENTRY_SIZE = 64 * 1024;

  @TempDir private Path m2Repo;
  private HttpServer server;
  private Repository repository;
  private byte[] jar;

  @BeforeEach
  void setUp() throws Exception {
    jar = newJar("remote");

    server = HttpServer.create(new InetSocketAddress(0), 0);
    serve(DIR + "/maven-metadata.xml", metadata().getBytes(StandardCharsets.UTF_8));
    serve(DIR + "/" + ARTIFACT_ID + "-" + TIMESTAMPED_VERSION + ".jar", jar);
    server.start();

    repository =
        new Repository()
            .id("central")
            .url("http://localhost:" + server.getAddress().getPort())
            .authz(null)
            .repoDir(m2Repo.toFile())
            .repoUpdatePolicy(UpdatePolicy.REMOTE);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * sanity-service and sbe-gateway declare {@code io.exberry:exchange-market-data-service} in two
   * properties files (mds-internal and mds-external). {@code Startables.deepStart} starts them in
   * parallel, so two resolutions of the same coordinate run concurrently and both publish the alias
   * with a non-atomic {@code Files.copy(REPLACE_EXISTING)} to the same path.
   */
  @Test
  void concurrentResolutionsOfTheSameSnapshotAllYieldReadableJars() throws Exception {
    final var concurrency = 8;
    final var rounds = 6;
    final var executor = Executors.newFixedThreadPool(concurrency);
    final var problems = new ArrayList<String>();

    try {
      for (int round = 0; round < rounds; round++) {
        // Each round starts cold, which is the dangerous case: every thread downloads.
        deleteRecursively(m2Repo.resolve("com"));

        final var barrier = new CyclicBarrier(concurrency);
        final var futures = new ArrayList<CompletableFuture<Path>>();
        for (int i = 0; i < concurrency; i++) {
          futures.add(
              CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      barrier.await();
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                    return new MavenResolver(repository).resolve(SPEC).join();
                  },
                  executor));
        }

        for (final var future : futures) {
          try {
            final var problem = verifyJar(future.join());
            if (problem != null) {
              problems.add("round " + round + ": " + problem);
            }
          } catch (Exception e) {
            final var cause = e.getCause() != null ? e.getCause() : e;
            problems.add(
                "round "
                    + round
                    + ": resolve failed -> "
                    + cause.getClass().getSimpleName()
                    + ": "
                    + cause.getMessage());
          }
        }
      }

      assertTrue(problems.isEmpty(), "Concurrent resolution problems: " + problems);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * The base-named {@code <artifactId>-<version>.jar} is Maven's slot for a locally installed
   * build. Nothing that resolves from a remote may write it - that is what makes the concurrency
   * race and the clobbering above impossible rather than merely unlikely.
   */
  @Test
  void resolutionNeverWritesTheBaseNamedSnapshotFile() throws Exception {
    final var resolved = new MavenResolver(repository).resolve(SPEC).join();
    final var baseNamed =
        m2Repo.resolve("com/foo/bar/1.0-SNAPSHOT").resolve(ARTIFACT_ID + "-" + VERSION + ".jar");

    assertEquals(
        ARTIFACT_ID + "-" + TIMESTAMPED_VERSION + ".jar",
        resolved.getFileName().toString(),
        "resolution must return the timestamped build, not a base-named alias");
    assertFalse(
        Files.exists(baseNamed),
        "resolution wrote " + baseNamed.getFileName() + ", which belongs to `mvn install`");
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (final var paths = Files.walk(root)) {
      for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  /**
   * A developer runs {@code mvn install} on a service, then runs the suite that starts it. The
   * locally installed {@code -SNAPSHOT.jar} must be what gets loaded, exactly as Maven's own {@code
   * localCopy} rule guarantees - and it must certainly not be overwritten.
   */
  @Test
  void locallyInstalledSnapshotIsNotOverwrittenAndIsIgnoredUnderRemotePolicy() throws Exception {
    final var dir = m2Repo.resolve("com/foo/bar/1.0-SNAPSHOT");
    Files.createDirectories(dir);

    final var installedJar = dir.resolve(ARTIFACT_ID + "-" + VERSION + ".jar");
    // A different payload than the remote build, so which one was served is detectable.
    final var installed = newJar("locally-built");
    Files.write(installedJar, installed);
    Files.writeString(dir.resolve("maven-metadata-local.xml"), localMetadata());

    final var resolved = new MavenResolver(repository).resolve(SPEC).join();

    // The bug: resolution used to copy the downloaded build onto the base name, which is the slot
    // `mvn install` owns. It must be left exactly as the developer built it.
    assertArrayEquals(
        installed,
        Files.readAllBytes(installedJar),
        "the locally installed -SNAPSHOT.jar was overwritten by the remote build");

    // Under REMOTE the locally installed build is irrelevant: the remote build is what is served.
    assertEquals(
        ARTIFACT_ID + "-" + TIMESTAMPED_VERSION + ".jar",
        resolved.getFileName().toString(),
        "REMOTE must serve the remote build, not the locally installed one");
    assertArrayEquals(jar, Files.readAllBytes(resolved), "resolved content");
  }

  private static String verifyJar(Path path) {
    try {
      if (!Files.exists(path)) {
        return path + " does not exist";
      }
      try (final var jarFile = new JarFile(path.toFile())) {
        var entries = 0;
        for (final var it = jarFile.entries(); it.hasMoreElements(); ) {
          final JarEntry entry = it.nextElement();
          try (final var in = jarFile.getInputStream(entry)) {
            in.readAllBytes();
          }
          entries++;
        }
        if (entries != ENTRY_COUNT) {
          return path.getFileName() + " has " + entries + " entries, expected " + ENTRY_COUNT;
        }
      }
      return null;
    } catch (Exception e) {
      return path.getFileName() + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  private static byte[] newJar(String marker) throws IOException {
    final var payload = new byte[ENTRY_SIZE];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i + marker.hashCode());
    }
    final var bytes = new ByteArrayOutputStream();
    try (final var out = new JarOutputStream(bytes)) {
      for (int i = 0; i < ENTRY_COUNT; i++) {
        final var entry =
            new ZipEntry("io/scalecube/generated/" + marker + "/Class" + i + ".class");
        out.putNextEntry(entry);
        out.write(payload);
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private void serve(String path, byte[] body) {
    server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          try (final var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    final var sha1 = sha1(body).getBytes(StandardCharsets.US_ASCII);
    server.createContext(
        path + ".sha1",
        exchange -> {
          exchange.sendResponseHeaders(200, sha1.length);
          try (final var out = exchange.getResponseBody()) {
            out.write(sha1);
          }
        });
  }

  private static String metadata() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata modelVersion="1.1.0">
          <groupId>com.foo</groupId>
          <artifactId>bar</artifactId>
          <version>1.0-SNAPSHOT</version>
          <versioning>
            <lastUpdated>20260821131138</lastUpdated>
            <snapshot>
              <timestamp>%s</timestamp>
              <buildNumber>%s</buildNumber>
            </snapshot>
            <snapshotVersions>
              <snapshotVersion>
                <extension>jar</extension>
                <value>%s</value>
                <updated>20260821131034</updated>
              </snapshotVersion>
            </snapshotVersions>
          </versioning>
        </metadata>
        """
        .formatted(TIMESTAMP, BUILD_NUMBER, TIMESTAMPED_VERSION);
  }

  private static String localMetadata() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata modelVersion="1.1.0">
          <groupId>com.foo</groupId>
          <artifactId>bar</artifactId>
          <versioning>
            <lastUpdated>20260828120000</lastUpdated>
            <snapshot>
              <localCopy>true</localCopy>
            </snapshot>
            <snapshotVersions>
              <snapshotVersion>
                <extension>jar</extension>
                <value>1.0-SNAPSHOT</value>
                <updated>20260828120000</updated>
              </snapshotVersion>
            </snapshotVersions>
          </versioning>
          <version>1.0-SNAPSHOT</version>
        </metadata>
        """;
  }

  private static String sha1(byte[] body) {
    try {
      final var digest = MessageDigest.getInstance("SHA-1").digest(body);
      final var sb = new StringBuilder();
      for (final byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
