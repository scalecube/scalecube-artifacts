package io.scalecube.artifacts.maven;

import static io.scalecube.artifacts.maven.Repository.REPO_DIR_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_ID_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_PASSWORD_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_RETRY_MAX_ATTEMPTS_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_UPDATE_POLICY_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_URL_PROP_NAME;
import static io.scalecube.artifacts.maven.Repository.REPO_USERNAME_PROP_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RepositoryTest {

  private static final String NULL_VALUE = "@null";

  @Test
  void testNullMarkerFallsBackToDefaults() {
    final var props = credentialedProps();
    props.setProperty(REPO_DIR_PROP_NAME, NULL_VALUE);
    props.setProperty(REPO_UPDATE_POLICY_PROP_NAME, NULL_VALUE);
    props.setProperty(REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, NULL_VALUE);
    props.setProperty(REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, NULL_VALUE);

    final var repository = Repository.newInstance(props);

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
    final var props = credentialedProps();
    props.setProperty(REPO_UPDATE_POLICY_PROP_NAME, "local");
    props.setProperty(REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, "3");
    props.setProperty(REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, "100");

    final var repository = Repository.newInstance(props);

    assertEquals(UpdatePolicy.LOCAL, repository.repoUpdatePolicy(), "repoUpdatePolicy");
    assertEquals(3, repository.retryMaxAttempts(), "retryMaxAttempts");
    assertEquals(100L, repository.retryInitialDelayMs(), "retryInitialDelayMs");
  }

  @Test
  void testNullMarkerOnRequiredIdFails() {
    final var props = credentialedProps();
    props.setProperty(REPO_ID_PROP_NAME, NULL_VALUE);

    assertThrows(IllegalArgumentException.class, () -> Repository.newInstance(props));
  }

  @Test
  void testNullMarkerOnRequiredUrlFails() {
    final var props = credentialedProps();
    props.setProperty(REPO_URL_PROP_NAME, NULL_VALUE);

    assertThrows(IllegalArgumentException.class, () -> Repository.newInstance(props));
  }

  @Test
  void testNullMarkerOnEnvPlaceholderFails() {
    final var props = new Properties();
    props.setProperty("GITHUB_TOKEN", NULL_VALUE);

    assertThrows(
        IllegalStateException.class, () -> Repository.unwrap("${env.GITHUB_TOKEN}", props));
  }

  private static Properties credentialedProps() {
    final var props = new Properties();
    props.setProperty(REPO_ID_PROP_NAME, "github");
    props.setProperty(
        REPO_URL_PROP_NAME, "https://maven.pkg.github.com/scalecube/scalecube-artifacts");
    props.setProperty(REPO_USERNAME_PROP_NAME, "user");
    props.setProperty(REPO_PASSWORD_PROP_NAME, "password");
    return props;
  }
}
