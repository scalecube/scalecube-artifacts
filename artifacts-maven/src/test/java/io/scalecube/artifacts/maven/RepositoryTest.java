package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RepositoryTest {

  private static final String NULL_VALUE = "@null";

  private static final String ID_PROP_NAME = "scalecube.artifacts.maven.repo.id";
  private static final String URL_PROP_NAME = "scalecube.artifacts.maven.repo.url";
  private static final String DIR_PROP_NAME = "scalecube.artifacts.maven.repo.dir";
  private static final String UPDATE_POLICY_PROP_NAME =
      "scalecube.artifacts.maven.repo.updatePolicy";
  private static final String RETRY_MAX_ATTEMPTS_PROP_NAME =
      "scalecube.artifacts.maven.repo.retryMaxAttempts";
  private static final String RETRY_INITIAL_DELAY_MS_PROP_NAME =
      "scalecube.artifacts.maven.repo.retryInitialDelayMs";
  private static final String USERNAME_PROP_NAME = "scalecube.artifacts.maven.repo.username";
  private static final String PASSWORD_PROP_NAME = "scalecube.artifacts.maven.repo.password";

  @Test
  void testNullMarkerFallsBackToDefaults() {
    final var props = credentialedProps();
    props.setProperty(DIR_PROP_NAME, NULL_VALUE);
    props.setProperty(UPDATE_POLICY_PROP_NAME, NULL_VALUE);
    props.setProperty(RETRY_MAX_ATTEMPTS_PROP_NAME, NULL_VALUE);
    props.setProperty(RETRY_INITIAL_DELAY_MS_PROP_NAME, NULL_VALUE);

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
    withMarker.setProperty(DIR_PROP_NAME, NULL_VALUE);
    withMarker.setProperty(UPDATE_POLICY_PROP_NAME, NULL_VALUE);
    withMarker.setProperty(RETRY_MAX_ATTEMPTS_PROP_NAME, NULL_VALUE);
    withMarker.setProperty(RETRY_INITIAL_DELAY_MS_PROP_NAME, NULL_VALUE);

    assertEquals(Repository.newInstance(credentialedProps()), Repository.newInstance(withMarker));
  }

  @Test
  void testExplicitValueWinsOverNullMarker() {
    final var props = credentialedProps();
    props.setProperty(UPDATE_POLICY_PROP_NAME, "local");
    props.setProperty(RETRY_MAX_ATTEMPTS_PROP_NAME, "3");
    props.setProperty(RETRY_INITIAL_DELAY_MS_PROP_NAME, "100");

    final var repository = Repository.newInstance(props);

    assertEquals(UpdatePolicy.LOCAL, repository.repoUpdatePolicy(), "repoUpdatePolicy");
    assertEquals(3, repository.retryMaxAttempts(), "retryMaxAttempts");
    assertEquals(100L, repository.retryInitialDelayMs(), "retryInitialDelayMs");
  }

  @Test
  void testNullMarkerOnRequiredIdFails() {
    final var props = credentialedProps();
    props.setProperty(ID_PROP_NAME, NULL_VALUE);

    assertThrows(IllegalArgumentException.class, () -> Repository.newInstance(props));
  }

  @Test
  void testNullMarkerOnRequiredUrlFails() {
    final var props = credentialedProps();
    props.setProperty(URL_PROP_NAME, NULL_VALUE);

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
    props.setProperty(ID_PROP_NAME, "github");
    props.setProperty(URL_PROP_NAME, "https://maven.pkg.github.com/scalecube/scalecube-artifacts");
    props.setProperty(USERNAME_PROP_NAME, "user");
    props.setProperty(PASSWORD_PROP_NAME, "password");
    return props;
  }
}
