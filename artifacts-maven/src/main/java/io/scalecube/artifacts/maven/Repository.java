package io.scalecube.artifacts.maven;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.NodeList;

public record Repository(
    String id,
    String url,
    String authz,
    File repoDir,
    UpdatePolicy repoUpdatePolicy,
    int retryMaxAttempts,
    long retryInitialDelayMs,
    boolean verifyCachedChecksum) {

  private static final System.Logger LOGGER = System.getLogger(Repository.class.getName());

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
  public static final String REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME =
      "scalecube.artifacts.maven.repo.verifyCachedChecksum";

  public static final UpdatePolicy DEFAULT_REPO_UPDATE_POLICY = UpdatePolicy.REMOTE;
  public static final int DEFAULT_REPO_RETRY_MAX_ATTEMPTS = 10;
  public static final long DEFAULT_REPO_RETRY_INITIAL_DELAY_MS = 3000L;
  public static final boolean DEFAULT_REPO_VERIFY_CACHED_CHECKSUM = true;

  public Repository(
      String id, String url, String authz, File repoDir, UpdatePolicy repoUpdatePolicy) {
    this(
        id,
        url,
        authz,
        repoDir,
        repoUpdatePolicy,
        DEFAULT_REPO_RETRY_MAX_ATTEMPTS,
        DEFAULT_REPO_RETRY_INITIAL_DELAY_MS);
  }

  public Repository(
      String id,
      String url,
      String authz,
      File repoDir,
      UpdatePolicy repoUpdatePolicy,
      int retryMaxAttempts,
      long retryInitialDelayMs) {
    this(
        id,
        url,
        authz,
        repoDir,
        repoUpdatePolicy,
        retryMaxAttempts,
        retryInitialDelayMs,
        DEFAULT_REPO_VERIFY_CACHED_CHECKSUM);
  }

  public static Repository newInstance(Properties properties) {
    final var id = repoId(properties);
    final var repoDir = repoDir(properties);

    return new Repository(
        id,
        repoUrl(properties),
        repoAuthorization(properties, id),
        repoDir,
        repoUpdatePolicy(properties),
        repoRetryMaxAttempts(properties),
        repoRetryInitialDelayMs(properties),
        repoVerifyCachedChecksum(properties));
  }

  public static URI remoteUri(Repository repository, String spec, String name) {
    final var url = repository.url();
    final var base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    return URI.create(base + "/" + Coordinates.parse(spec).directory() + "/" + name);
  }

  public static Path localFile(Repository repository, String spec, String name) {
    return localDir(repository, spec).resolve(name);
  }

  /** The local-repository directory holding every file of {@code spec}'s version. */
  public static Path localDir(Repository repository, String spec) {
    return repository.repoDir().toPath().resolve(Coordinates.parse(spec).directory());
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

  private static UpdatePolicy getProperty(
      Properties properties, String name, UpdatePolicy defaultValue) {
    final var value = getProperty(properties, name);
    return value != null ? UpdatePolicy.valueOf(value.toUpperCase()) : defaultValue;
  }

  private static boolean getProperty(Properties properties, String name, boolean defaultValue) {
    final var value = getProperty(properties, name);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  private static String requireProperty(Properties properties, String name, String description) {
    final var value = getProperty(properties, name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(description + " is missing or invalid");
    }
    return value;
  }

  private static File repoDir(Properties properties) {
    final var dir = getProperty(properties, REPO_DIR_PROP_NAME);
    if (dir == null || dir.isEmpty()) {
      return Path.of(System.getProperty("user.home"), ".m2", "repository").toFile();
    }
    try {
      return Path.of(dir).toFile().getCanonicalFile();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String repoId(Properties properties) {
    return requireProperty(properties, REPO_ID_PROP_NAME, "repository id");
  }

  private static String repoUrl(Properties properties) {
    return requireProperty(properties, REPO_URL_PROP_NAME, "repository url");
  }

  private static UpdatePolicy repoUpdatePolicy(Properties properties) {
    return getProperty(properties, REPO_UPDATE_POLICY_PROP_NAME, DEFAULT_REPO_UPDATE_POLICY);
  }

  private static int repoRetryMaxAttempts(Properties properties) {
    return getProperty(
        properties, REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, DEFAULT_REPO_RETRY_MAX_ATTEMPTS);
  }

  private static long repoRetryInitialDelayMs(Properties properties) {
    return getProperty(
        properties, REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, DEFAULT_REPO_RETRY_INITIAL_DELAY_MS);
  }

  /**
   * Returns the settings.xml to read credentials from. Independent of {@link #REPO_DIR_PROP_NAME},
   * so pointing the local repository at another directory does not move the settings lookup with
   * it.
   */
  private static Path repoSettings(Properties properties) {
    final var settings = getProperty(properties, REPO_SETTINGS_PROP_NAME);
    if (settings == null || settings.isEmpty()) {
      return Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
    }
    return Path.of(settings);
  }

  /**
   * Returns the {@code Authorization} header value, or null when no credentials are configured. A
   * missing settings.xml or a missing {@code <server>} is not an error: a public repository needs
   * none, and an artifact already in the local repository is served without any request. An
   * unexpandable {@code ${env.NAME}} still fails, see {@link #unwrap}.
   */
  private static boolean repoVerifyCachedChecksum(Properties properties) {
    return getProperty(
        properties, REPO_VERIFY_CACHED_CHECKSUM_PROP_NAME, DEFAULT_REPO_VERIFY_CACHED_CHECKSUM);
  }

  private static String repoAuthorization(Properties properties, String repoId) {
    final var username = getProperty(properties, REPO_USERNAME_PROP_NAME);
    final var password = getProperty(properties, REPO_PASSWORD_PROP_NAME);

    if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
      return "Basic " + encodeCredentials(username, password);
    }

    return credentialsFromSettings(repoId, repoSettings(properties), properties);
  }

  private static String encodeCredentials(String username, String password) {
    return Base64.getEncoder()
        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String credentialsFromSettings(String id, Path settings, Properties properties) {
    if (Files.notExists(settings)) {
      LOGGER.log(Level.DEBUG, () -> settings + " does not exist, continuing without credentials");
      return null;
    }

    try (var is = Files.newInputStream(settings)) {
      final var factory = DocumentBuilderFactory.newInstance();
      final var doc = factory.newDocumentBuilder().parse(is);
      final var xPath = XPathFactory.newInstance().newXPath();

      final var servers =
          (NodeList) xPath.evaluate("//server[id='" + id + "']", doc, XPathConstants.NODESET);

      if (servers.getLength() == 0) {
        LOGGER.log(Level.WARNING, () -> "No server found with id=" + id + " in " + settings);
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
