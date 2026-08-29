package io.scalecube.artifacts.maven;

import static io.scalecube.artifacts.maven.Repository.DEFAULT_REPO_RETRY_INITIAL_DELAY_MS;
import static io.scalecube.artifacts.maven.Repository.DEFAULT_REPO_RETRY_MAX_ATTEMPTS;
import static io.scalecube.artifacts.maven.Repository.DEFAULT_REPO_RETRY_MAX_DELAY_MS;
import static io.scalecube.artifacts.maven.Repository.DEFAULT_REPO_UPDATE_POLICY;
import static io.scalecube.artifacts.maven.Repository.DEFAULT_REPO_VERIFY_CACHED_CHECKSUM;
import static io.scalecube.artifacts.maven.Repository.REPO_DIR_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_ID_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_PASSWORD_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_RETRY_MAX_ATTEMPTS_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_RETRY_MAX_DELAY_MS_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_SETTINGS_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_UPDATE_POLICY_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_URL_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_USERNAME_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTest {

  private static final String NULL_VALUE = "@null";

  @Test
  void testNullMarkerFallsBackToDefaults() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_DIR_PROP_NAME, NULL_VALUE);
    properties.setProperty(REPO_UPDATE_POLICY_PROP_NAME, NULL_VALUE);
    properties.setProperty(REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, NULL_VALUE);
    properties.setProperty(REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, NULL_VALUE);

    final var repository = concluded(properties);

    assertEquals(
        Path.of(System.getProperty("user.home"), ".m2", "repository").toFile(),
        repository.repoDir(),
        "repoDir");
    assertEquals(UpdatePolicy.REMOTE, repository.repoUpdatePolicy(), "repoUpdatePolicy");
    assertEquals(10, repository.retryMaxAttempts(), "retryMaxAttempts");
    assertEquals(3000L, repository.retryInitialDelayMs(), "retryInitialDelayMs");
  }

  @Test
  void testNullMarkerYieldsSameResultAsAbsentProperty() {
    final var withMarker = credentialedProps();
    withMarker.setProperty(REPO_DIR_PROP_NAME, NULL_VALUE);
    withMarker.setProperty(REPO_UPDATE_POLICY_PROP_NAME, NULL_VALUE);
    withMarker.setProperty(REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, NULL_VALUE);
    withMarker.setProperty(REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, NULL_VALUE);

    final var absent = concluded(credentialedProps());
    final var marked = concluded(withMarker);

    assertEquals(absent.id(), marked.id(), "id");
    assertEquals(absent.url(), marked.url(), "url");
    assertEquals(absent.authz(), marked.authz(), "authz");
    assertEquals(absent.repoDir(), marked.repoDir(), "repoDir");
    assertEquals(absent.repoUpdatePolicy(), marked.repoUpdatePolicy(), "repoUpdatePolicy");
    assertEquals(absent.retryMaxAttempts(), marked.retryMaxAttempts(), "retryMaxAttempts");
    assertEquals(absent.retryInitialDelayMs(), marked.retryInitialDelayMs(), "retryInitialDelayMs");
    assertEquals(
        absent.verifyCachedChecksum(), marked.verifyCachedChecksum(), "verifyCachedChecksum");
  }

  @Test
  void testExplicitValueWinsOverNullMarker() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_UPDATE_POLICY_PROP_NAME, "local");
    properties.setProperty(REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, "3");
    properties.setProperty(REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, "100");

    final var repository = concluded(properties);

    assertEquals(UpdatePolicy.LOCAL, repository.repoUpdatePolicy(), "repoUpdatePolicy");
    assertEquals(3, repository.retryMaxAttempts(), "retryMaxAttempts");
    assertEquals(100L, repository.retryInitialDelayMs(), "retryInitialDelayMs");
  }

  @Test
  void testNullMarkerOnRequiredIdFails() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_ID_PROP_NAME, NULL_VALUE);

    assertThrows(IllegalArgumentException.class, () -> concluded(properties));
  }

  @Test
  void testNullMarkerOnRequiredUrlFails() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_URL_PROP_NAME, NULL_VALUE);

    assertThrows(IllegalArgumentException.class, () -> concluded(properties));
  }

  @Test
  void testNullMarkerOnEnvPlaceholderFails() {
    final var properties = new Properties();
    properties.setProperty("GITHUB_TOKEN", NULL_VALUE);

    assertThrows(
        IllegalStateException.class, () -> Repository.unwrap("${env.GITHUB_TOKEN}", properties));
  }

  @Test
  void testAbsentSettingsFileYieldsNoCredentials(@TempDir Path dir) {
    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, dir.resolve("nope.xml").toString());

    assertNull(concluded(properties).authz(), "authz");
  }

  @Test
  void testSettingsWithoutMatchingServerYieldsNoCredentials(@TempDir Path dir) throws IOException {
    final var settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><servers><server><id>other</id>"
            + "<username>u</username><password>p</password></server></servers></settings>");

    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, settings.toString());

    assertNull(concluded(properties).authz(), "authz");
  }

  @Test
  void testCredentialsReadFromSettingsPath(@TempDir Path dir) throws IOException {
    final var settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><servers><server><id>github</id>"
            + "<username>u</username><password>p</password></server></servers></settings>");

    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, settings.toString());

    final var authz = concluded(properties).authz();

    assertNotNull(authz, "authz");
    assertEquals("Basic dTpw", authz, "authz");
  }

  @Test
  void testEnvPlaceholderInSettingsIsResolved(@TempDir Path dir) throws IOException {
    final var settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><servers><server><id>github</id>"
            + "<username>${env.GITHUB_USER}</username>"
            + "<password>${env.GITHUB_TOKEN}</password></server></servers></settings>");

    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, settings.toString());
    // placeholders resolve from these properties, not from the process environment
    properties.setProperty("GITHUB_USER", "my-user");
    properties.setProperty("GITHUB_TOKEN", "s3cret");

    // Basic base64("my-user:s3cret")
    assertEquals("Basic bXktdXNlcjpzM2NyZXQ=", concluded(properties).authz(), "authz");
  }

  @Test
  void testRawAndPlaceholderCredentialsCanBeMixed(@TempDir Path dir) throws IOException {
    final var settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><servers><server><id>github</id>"
            + "<username>plain-user</username>"
            + "<password>${env.GITHUB_TOKEN}</password></server></servers></settings>");

    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, settings.toString());
    properties.setProperty("GITHUB_TOKEN", "tok-123");

    // the raw username is passed through untouched, only the password is resolved
    // Basic base64("plain-user:tok-123")
    assertEquals("Basic cGxhaW4tdXNlcjp0b2stMTIz", concluded(properties).authz(), "authz");
  }

  @Test
  void testUnresolvableEnvPlaceholderInSettingsThrows(@TempDir Path dir) throws IOException {
    final var settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><servers><server><id>github</id>"
            + "<username>u</username>"
            + "<password>${env.GITHUB_TOKEN}</password></server></servers></settings>");

    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, settings.toString());
    // GITHUB_TOKEN deliberately not set: sending the literal placeholder as a password would
    // only produce a confusing 401, so this must fail up front

    assertThrows(IllegalStateException.class, () -> concluded(properties));
  }

  @Test
  void testVerifyCachedChecksumDefaultsToTrue() {
    assertTrue(concluded(credentialedProps()).verifyCachedChecksum(), "verifyCachedChecksum");
  }

  @Test
  void testVerifyCachedChecksumCanBeTurnedOff() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME, "false");

    assertFalse(concluded(properties).verifyCachedChecksum(), "verifyCachedChecksum");
  }

  @Test
  void testNullMarkerOnVerifyCachedChecksumFallsBackToDefault() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME, NULL_VALUE);

    assertTrue(concluded(properties).verifyCachedChecksum(), "verifyCachedChecksum");
  }

  @Test
  void testAbsentPropertyAppliesDefault() {
    final var repository =
        new Repository()
            .id("central")
            .url("http://localhost")
            .repoUpdatePolicy(UpdatePolicy.LOCAL)
            .retryMaxAttempts(2)
            .retryInitialDelayMs(50)
            .verifyCachedChecksum(false);

    // the default lives in the Properties setter, so an absent property applies it
    final var properties = new Properties();
    repository
        .repoUpdatePolicy(properties)
        .retryMaxAttempts(properties)
        .retryInitialDelayMs(properties)
        .retryMaxDelayMs(properties)
        .verifyCachedChecksum(properties);

    assertEquals(DEFAULT_REPO_UPDATE_POLICY, repository.repoUpdatePolicy(), "repoUpdatePolicy");
    assertEquals(
        DEFAULT_REPO_RETRY_MAX_ATTEMPTS, repository.retryMaxAttempts(), "retryMaxAttempts");
    assertEquals(
        DEFAULT_REPO_RETRY_INITIAL_DELAY_MS,
        repository.retryInitialDelayMs(),
        "retryInitialDelayMs");
    assertEquals(DEFAULT_REPO_RETRY_MAX_DELAY_MS, repository.retryMaxDelayMs(), "retryMaxDelayMs");
    assertEquals(
        DEFAULT_REPO_VERIFY_CACHED_CHECKSUM,
        repository.verifyCachedChecksum(),
        "verifyCachedChecksum");
  }

  @Test
  void testPropertyOverridesValueSetFromCode() {
    final var repository =
        new Repository().id("central").url("http://localhost").retryMaxAttempts(2);

    final var properties = new Properties();
    properties.setProperty(REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, "7");

    assertEquals(7, repository.retryMaxAttempts(properties).retryMaxAttempts(), "retryMaxAttempts");
  }

  @Test
  void testRetryMaxDelayMsReadFromProperty() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_RETRY_MAX_DELAY_MS_PROP_NAME, "1234");

    assertEquals(1234L, concluded(properties).retryMaxDelayMs(), "retryMaxDelayMs");
  }

  private static Repository concluded(Properties properties) {
    return new Repository(properties).conclude();
  }

  private static Properties credentialedProps() {
    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(
        REPO_URL_PROP_NAME, "https://maven.pkg.github.com/scalecube/scalecube-artifacts");
    properties.setProperty(REPO_USERNAME_PROP_NAME, "user");
    properties.setProperty(REPO_PASSWORD_PROP_NAME, "password");
    return properties;
  }
}
