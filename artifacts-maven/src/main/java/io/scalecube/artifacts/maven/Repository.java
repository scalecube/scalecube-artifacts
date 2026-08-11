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
import org.w3c.dom.NodeList;

public record Repository(
    String id,
    String url,
    String authz,
    File repoDir,
    UpdatePolicy repoUpdatePolicy,
    int retryMaxAttempts,
    long retryInitialDelayMs) {

  public static final String REPO_DIR_PROP_NAME = "scalecube.artifacts.maven.repo.dir";
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

  public static final UpdatePolicy DEFAULT_REPO_UPDATE_POLICY = UpdatePolicy.REMOTE;
  public static final int DEFAULT_REPO_RETRY_MAX_ATTEMPTS = 10;
  public static final long DEFAULT_REPO_RETRY_INITIAL_DELAY_MS = 3000L;

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

  public static Repository newInstance(Properties properties) {
    final var id = repoId(properties);
    final var repoDir = repoDir(properties);

    return new Repository(
        id,
        repoUrl(properties),
        repoAuthorization(properties, repoDir, id),
        repoDir,
        repoUpdatePolicy(properties),
        repoRetryMaxAttempts(properties),
        repoRetryInitialDelayMs(properties));
  }

  public static URI remoteUri(Repository repository, String spec, String name) {
    final var split = spec.split(":");

    if (split.length != 3) {
      throw new IllegalArgumentException("Wrong format: " + spec);
    }

    final var groupId = split[0].replace(".", "/");
    final var artifactId = split[1];
    final var version = split[2];

    return URI.create(String.join("/", repository.url(), groupId, artifactId, version, name));
  }

  public static Path localFile(Repository repository, String spec, String name) {
    final var split = spec.split(":");

    if (split.length != 3) {
      throw new IllegalArgumentException("Wrong format: " + spec);
    }

    final var groupId = split[0].replace(".", "/");
    final var artifactId = split[1];
    final var version = split[2];

    return repository
        .repoDir()
        .toPath()
        .resolve(Path.of(groupId, artifactId, version))
        .resolve(name);
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

  private static String repoAuthorization(Properties properties, File repoDir, String repoId) {
    final var username = getProperty(properties, REPO_USERNAME_PROP_NAME);
    final var password = getProperty(properties, REPO_PASSWORD_PROP_NAME);

    String authorization;
    if ((username == null || username.isEmpty()) && (password == null || password.isEmpty())) {
      final var settings = Path.of(repoDir.getParent()).resolve("settings.xml");
      authorization = "Basic " + encodeCredentialsFromSettings(repoId, settings, properties);
    } else {
      authorization = "Basic " + encodeCredentials(username, password);
    }

    return authorization;
  }

  private static String encodeCredentials(String username, String password) {
    return Base64.getEncoder()
        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String encodeCredentialsFromSettings(
      String id, Path settings, Properties properties) {
    if (Files.notExists(settings)) {
      throw new IllegalStateException(settings + " - not exist");
    }

    try (var is = Files.newInputStream(settings)) {
      final var factory = DocumentBuilderFactory.newInstance();
      final var doc = factory.newDocumentBuilder().parse(is);
      final var xPath = XPathFactory.newInstance().newXPath();

      final var servers =
          (NodeList) xPath.evaluate("//server[id='" + id + "']", doc, XPathConstants.NODESET);

      if (servers.getLength() == 0) {
        throw new IllegalArgumentException("No server found with id=" + id);
      }

      final var server = servers.item(0);
      final var username = unwrap(xPath.evaluate("username", server), properties);
      final var password = unwrap(xPath.evaluate("password", server), properties);

      return encodeCredentials(username, password);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse " + settings, e);
    }
  }

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
