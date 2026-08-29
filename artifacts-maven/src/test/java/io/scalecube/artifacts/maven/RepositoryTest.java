package io.scalecube.artifacts.maven;

import static io.scalecube.artifacts.maven.Repository.REPO_DIR_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_ID_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_PASSWORD_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_RETRY_MAX_ATTEMPTS_PROP_NAME;
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

    final var repository = Repository.newInstance(properties);

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

    assertEquals(Repository.newInstance(credentialedProps()), Repository.newInstance(withMarker));
  }

  @Test
  void testExplicitValueWinsOverNullMarker() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_UPDATE_POLICY_PROP_NAME, "local");
    properties.setProperty(REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, "3");
    properties.setProperty(REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, "100");

    final var repository = Repository.newInstance(properties);

    assertEquals(UpdatePolicy.LOCAL, repository.repoUpdatePolicy(), "repoUpdatePolicy");
    assertEquals(3, repository.retryMaxAttempts(), "retryMaxAttempts");
    assertEquals(100L, repository.retryInitialDelayMs(), "retryInitialDelayMs");
  }

  @Test
  void testNullMarkerOnRequiredIdFails() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_ID_PROP_NAME, NULL_VALUE);

    assertThrows(IllegalArgumentException.class, () -> Repository.newInstance(properties));
  }

  @Test
  void testNullMarkerOnRequiredUrlFails() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_URL_PROP_NAME, NULL_VALUE);

    assertThrows(IllegalArgumentException.class, () -> Repository.newInstance(properties));
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

    assertNull(Repository.newInstance(properties).authz(), "authz");
  }

  @Test
  void testSettingsWithoutMatchingServerYieldsNoCredentials(@TempDir Path dir) throws IOException {
    final var settings = dir.resolve("settings.xml");
    Files.writeString(settings, "<settings><servers><server><id>other</id>"
        + "<username>u</username><password>p</password></server></servers></settings>");

    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, settings.toString());

    assertNull(Repository.newInstance(properties).authz(), "authz");
  }

  @Test
  void testCredentialsReadFromSettingsPath(@TempDir Path dir) throws IOException {
    final var settings = dir.resolve("settings.xml");
    Files.writeString(settings, "<settings><servers><server><id>github</id>"
        + "<username>u</username><password>p</password></server></servers></settings>");

    final var properties = new Properties();
    properties.setProperty(REPO_ID_PROP_NAME, "github");
    properties.setProperty(REPO_URL_PROP_NAME, "https://example.com/repo");
    properties.setProperty(REPO_SETTINGS_PROP_NAME, settings.toString());

    final var authz = Repository.newInstance(properties).authz();

    assertNotNull(authz, "authz");
    assertEquals("Basic dTpw", authz, "authz");
  }

  @Test
  void testVerifyCachedChecksumDefaultsToTrue() {
    assertTrue(
        Repository.newInstance(credentialedProps()).verifyCachedChecksum(),
        "verifyCachedChecksum");
  }

  @Test
  void testVerifyCachedChecksumCanBeTurnedOff() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME, "false");

    assertFalse(
        Repository.newInstance(properties).verifyCachedChecksum(), "verifyCachedChecksum");
  }

  @Test
  void testNullMarkerOnVerifyCachedChecksumFallsBackToDefault() {
    final var properties = credentialedProps();
    properties.setProperty(REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME, NULL_VALUE);

    assertTrue(
        Repository.newInstance(properties).verifyCachedChecksum(), "verifyCachedChecksum");
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
