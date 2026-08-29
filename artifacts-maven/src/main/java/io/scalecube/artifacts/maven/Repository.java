package io.scalecube.artifacts.maven;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;

public class Repository {

  private static final Logger LOGGER = LoggerFactory.getLogger(Repository.class);

  public static final String REPO_DIR_PROP_NAME = "scalecube.artifacts.maven.repo.dir";
  public static final String REPO_SETTINGS_PROP_NAME = "scalecube.artifacts.maven.repo.settings";
  public static final String REPO_ID_PROP_NAME = "scalecube.artifacts.maven.repo.id";
  public static final String REPO_URL_PROP_NAME = "scalecube.artifacts.maven.repo.url";
  public static final String REPO_USERNAME_PROP_NAME = "scalecube.artifacts.maven.repo.username";
  public static final String REPO_PASSWORD_PROP_NAME = "scalecube.artifacts.maven.repo.password";
  public static final String REPO_UPDATE_POLICY_PROP_NAME =
      "scalecube.artifacts.maven.repo.updatePolicy";
  public static final String REPO_RETRY_MAX_ATTEMPTS_PROP_NAME =
      "scalecube.artifacts.maven.repo.retryMaxAttempts";
  public static final String REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME =
      "scalecube.artifacts.maven.repo.retryInitialDelayMs";
  public static final String REPO_RETRY_MAX_DELAY_MS_PROP_NAME =
      "scalecube.artifacts.maven.repo.retryMaxDelayMs";
  public static final String REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME =
      "scalecube.artifacts.maven.repo.verifyCachedChecksum";

  public static final UpdatePolicy DEFAULT_REPO_UPDATE_POLICY = UpdatePolicy.REMOTE;
  public static final int DEFAULT_REPO_RETRY_MAX_ATTEMPTS = 10;
  public static final long DEFAULT_REPO_RETRY_INITIAL_DELAY_MS = 3000L;
  public static final long DEFAULT_REPO_RETRY_MAX_DELAY_MS = 60_000L;
  public static final boolean DEFAULT_REPO_VERIFY_CACHED_CHECKSUM = true;

  private String id;
  private String url;
  private String authz;
  private String username;
  private String password;
  private File repoDir;
  private File settings;
  private UpdatePolicy repoUpdatePolicy;
  private int retryMaxAttempts;
  private long retryInitialDelayMs;
  private long retryMaxDelayMs;
  private boolean verifyCachedChecksum;
  private final Properties properties;

  public Repository() {
    this(System.getProperties());
  }

  public Repository(Properties properties) {
    this.properties = properties;
    id(properties);
    url(properties);
    username(properties);
    password(properties);
    repoDir(properties);
    settings(properties);
    repoUpdatePolicy(properties);
    retryMaxAttempts(properties);
    retryInitialDelayMs(properties);
    retryMaxDelayMs(properties);
    verifyCachedChecksum(properties);
  }

  public Repository conclude() {
    if (id == null || id.isEmpty()) {
      throw new IllegalArgumentException("repository id is missing or invalid");
    }
    if (url == null || url.isEmpty()) {
      throw new IllegalArgumentException("repository url is missing or invalid");
    }

    if (repoDir == null) {
      repoDir = Path.of(System.getProperty("user.home"), ".m2", "repository").toFile();
    }
    if (settings == null) {
      settings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml").toFile();
    }
    if (authz == null) {
      authz = resolveAuthorization();
    }
    return this;
  }

  public static URI remoteUri(Repository repository, String spec, String name) {
    final var url = repository.url();
    final var base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    return URI.create(base + "/" + Coordinates.parse(spec).directory() + "/" + name);
  }

  public static Path localFile(Repository repository, String spec, String name) {
    return localDir(repository, spec).resolve(name);
  }

  /**
   * Returns the local directory that holds every file of the given spec's version, for example
   * {@code ~/.m2/repository/com/foo/bar/1.0-SNAPSHOT}.
   *
   * @param repository artifact repository
   * @param spec artifact coordinate
   * @return local directory for that version
   */
  public static Path localDir(Repository repository, String spec) {
    final var root = repository.repoDir().toPath().toAbsolutePath().normalize();
    final var dir = root.resolve(Coordinates.parse(spec).directory()).normalize();
    if (!dir.startsWith(root)) {
      throw new IllegalArgumentException("Artifact directory escapes the repository: " + spec);
    }
    return dir;
  }

  public String id() {
    return id;
  }

  public Repository id(String id) {
    this.id = id;
    return this;
  }

  public Repository id(Properties properties) {
    final var value = getProperty(properties, REPO_ID_PROP_NAME);
    return value == null ? this : id(value);
  }

  public String url() {
    return url;
  }

  public Repository url(String url) {
    this.url = url;
    return this;
  }

  public Repository url(Properties properties) {
    final var value = getProperty(properties, REPO_URL_PROP_NAME);
    return value == null ? this : url(value);
  }

  public String authz() {
    return authz;
  }

  public Repository authz(String authz) {
    this.authz = authz;
    return this;
  }

  public String username() {
    return username;
  }

  public Repository username(String username) {
    this.username = username;
    return this;
  }

  public Repository username(Properties properties) {
    final var value = getProperty(properties, REPO_USERNAME_PROP_NAME);
    return value == null ? this : username(value);
  }

  public String password() {
    return password;
  }

  public Repository password(String password) {
    this.password = password;
    return this;
  }

  public Repository password(Properties properties) {
    final var value = getProperty(properties, REPO_PASSWORD_PROP_NAME);
    return value == null ? this : password(value);
  }

  public File repoDir() {
    return repoDir;
  }

  public Repository repoDir(File repoDir) {
    this.repoDir = repoDir;
    return this;
  }

  public Repository repoDir(Properties properties) {
    final var value = getProperty(properties, REPO_DIR_PROP_NAME);
    if (value == null || value.isEmpty()) {
      return this;
    }
    try {
      return repoDir(Path.of(value).toFile().getCanonicalFile());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the settings.xml that credentials are read from. This is independent of the local
   * repository directory: the two are configured separately and either can be moved on its own.
   *
   * @return settings.xml file
   */
  public File settings() {
    return settings;
  }

  public Repository settings(File settings) {
    this.settings = settings;
    return this;
  }

  public Repository settings(Properties properties) {
    final var value = getProperty(properties, REPO_SETTINGS_PROP_NAME);
    return value == null || value.isEmpty() ? this : settings(Path.of(value).toFile());
  }

  public UpdatePolicy repoUpdatePolicy() {
    return repoUpdatePolicy;
  }

  public Repository repoUpdatePolicy(UpdatePolicy repoUpdatePolicy) {
    this.repoUpdatePolicy = repoUpdatePolicy;
    return this;
  }

  public Repository repoUpdatePolicy(Properties properties) {
    final var value = getProperty(properties, REPO_UPDATE_POLICY_PROP_NAME);
    return repoUpdatePolicy(
        value != null ? UpdatePolicy.valueOf(value.toUpperCase()) : DEFAULT_REPO_UPDATE_POLICY);
  }

  public int retryMaxAttempts() {
    return retryMaxAttempts;
  }

  public Repository retryMaxAttempts(int retryMaxAttempts) {
    this.retryMaxAttempts = retryMaxAttempts;
    return this;
  }

  public Repository retryMaxAttempts(Properties properties) {
    return retryMaxAttempts(
        getProperty(
            properties, REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, DEFAULT_REPO_RETRY_MAX_ATTEMPTS));
  }

  public long retryInitialDelayMs() {
    return retryInitialDelayMs;
  }

  public Repository retryInitialDelayMs(long retryInitialDelayMs) {
    this.retryInitialDelayMs = retryInitialDelayMs;
    return this;
  }

  public Repository retryInitialDelayMs(Properties properties) {
    return retryInitialDelayMs(
        getProperty(
            properties,
            REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME,
            DEFAULT_REPO_RETRY_INITIAL_DELAY_MS));
  }

  /**
   * Returns the ceiling for the exponential retry backoff. Without it the doubling delay grows
   * without bound, so a long retry run would end up sleeping for hours.
   *
   * @return maximum delay between retries, in milliseconds
   */
  public long retryMaxDelayMs() {
    return retryMaxDelayMs;
  }

  public Repository retryMaxDelayMs(long retryMaxDelayMs) {
    this.retryMaxDelayMs = retryMaxDelayMs;
    return this;
  }

  public Repository retryMaxDelayMs(Properties properties) {
    return retryMaxDelayMs(
        getProperty(
            properties, REPO_RETRY_MAX_DELAY_MS_PROP_NAME, DEFAULT_REPO_RETRY_MAX_DELAY_MS));
  }

  public boolean verifyCachedChecksum() {
    return verifyCachedChecksum;
  }

  public Repository verifyCachedChecksum(boolean verifyCachedChecksum) {
    this.verifyCachedChecksum = verifyCachedChecksum;
    return this;
  }

  public Repository verifyCachedChecksum(Properties properties) {
    return verifyCachedChecksum(
        getProperty(
            properties,
            REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME,
            DEFAULT_REPO_VERIFY_CACHED_CHECKSUM));
  }

  private static String getProperty(Properties properties, String name) {
    final var value = properties.getProperty(name);
    return "@null".equals(value) ? null : value;
  }

  private static int getProperty(Properties properties, String name, int defaultValue) {
    final var value = getProperty(properties, name);
    return value != null ? Integer.parseInt(value) : defaultValue;
  }

  private static long getProperty(Properties properties, String name, long defaultValue) {
    final var value = getProperty(properties, name);
    return value != null ? Long.parseLong(value) : defaultValue;
  }

  private static boolean getProperty(Properties properties, String name, boolean defaultValue) {
    final var value = getProperty(properties, name);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  /**
   * Returns the Authorization header value, or null when no credentials are configured. A missing
   * settings.xml or a missing server entry is not an error: a public repository needs none, and an
   * artifact already in the local repository is served without any request. An unexpandable {@code
   * ${env.NAME}} still fails, see {@link #unwrap}.
   */
  private String resolveAuthorization() {
    if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
      return "Basic " + encodeCredentials(username, password);
    }
    return credentialsFromSettings(id, settings.toPath(), properties);
  }

  private static String encodeCredentials(String username, String password) {
    return Base64.getEncoder()
        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String credentialsFromSettings(String id, Path settings, Properties properties) {
    if (Files.notExists(settings)) {
      LOGGER.debug("{} does not exist, continuing without credentials", settings);
      return null;
    }

    try (var is = Files.newInputStream(settings)) {
      final var factory = DocumentBuilderFactory.newInstance();
      final var doc = factory.newDocumentBuilder().parse(is);
      final var xPath = XPathFactory.newInstance().newXPath();

      final var servers =
          (NodeList) xPath.evaluate("//server[id='" + id + "']", doc, XPathConstants.NODESET);

      if (servers.getLength() == 0) {
        LOGGER.warn("No server found with id={} in {}", id, settings);
        return null;
      }

      final var server = servers.item(0);
      final var username = unwrap(xPath.evaluate("username", server), properties);
      final var password = unwrap(xPath.evaluate("password", server), properties);

      return "Basic " + encodeCredentials(username, password);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse " + settings, e);
    }
  }

  /**
   * Expands a {@code ${env.NAME}} placeholder from the given properties. Fails when the variable is
   * not set, because sending the literal {@code ${env.NAME}} as a password would only produce a
   * confusing 401.
   */
  static String unwrap(String value, Properties properties) {
    if (value != null && value.startsWith("${env.") && value.endsWith("}")) {
      final var varName = value.substring(6, value.length() - 1);
      final var envValue = getProperty(properties, varName);
      if (envValue == null || envValue.isEmpty()) {
        throw new IllegalStateException(
            "Environment variable is missing or invalid (name=" + varName + ")");
      }
      return envValue;
    }
    return value;
  }
}
