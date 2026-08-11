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

  public static Repository newInstance(Properties props) {
    final var id = repoId(props);
    final var repoDir = repoDir(props);

    return new Repository(
        id,
        repoUrl(props),
        repoAuthorization(props, repoDir, id),
        repoDir,
        repoUpdatePolicy(props),
        repoRetryMaxAttempts(props),
        repoRetryInitialDelayMs(props));
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

  private static String getProperty(Properties props, String name) {
    final var value = props.getProperty(name);
    return "@null".equals(value) ? null : value;
  }

  private static int getProperty(Properties props, String name, int defaultValue) {
    final var value = getProperty(props, name);
    return value != null ? Integer.parseInt(value) : defaultValue;
  }

  private static long getProperty(Properties props, String name, long defaultValue) {
    final var value = getProperty(props, name);
    return value != null ? Long.parseLong(value) : defaultValue;
  }

  private static UpdatePolicy getProperty(
      Properties props, String name, UpdatePolicy defaultValue) {
    final var value = getProperty(props, name);
    return value != null ? UpdatePolicy.valueOf(value.toUpperCase()) : defaultValue;
  }

  private static String requireProperty(Properties props, String name, String description) {
    final var value = getProperty(props, name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(description + " is missing or invalid");
    }
    return value;
  }

  private static File repoDir(Properties props) {
    final var dir = getProperty(props, REPO_DIR_PROP_NAME);
    if (dir == null || dir.isEmpty()) {
      return Path.of(System.getProperty("user.home"), ".m2", "repository").toFile();
    }
    try {
      return Path.of(dir).toFile().getCanonicalFile();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String repoId(Properties props) {
    return requireProperty(props, REPO_ID_PROP_NAME, "repository id");
  }

  private static String repoUrl(Properties props) {
    return requireProperty(props, REPO_URL_PROP_NAME, "repository url");
  }

  private static UpdatePolicy repoUpdatePolicy(Properties props) {
    return getProperty(props, REPO_UPDATE_POLICY_PROP_NAME, DEFAULT_REPO_UPDATE_POLICY);
  }

  private static int repoRetryMaxAttempts(Properties props) {
    return getProperty(props, REPO_RETRY_MAX_ATTEMPTS_PROP_NAME, DEFAULT_REPO_RETRY_MAX_ATTEMPTS);
  }

  private static long repoRetryInitialDelayMs(Properties props) {
    return getProperty(
        props, REPO_RETRY_INITIAL_DELAY_MS_PROP_NAME, DEFAULT_REPO_RETRY_INITIAL_DELAY_MS);
  }

  private static String repoAuthorization(Properties props, File repoDir, String repoId) {
    final var username = getProperty(props, REPO_USERNAME_PROP_NAME);
    final var password = getProperty(props, REPO_PASSWORD_PROP_NAME);

    String authorization;
    if ((username == null || username.isEmpty()) && (password == null || password.isEmpty())) {
      final var settings = Path.of(repoDir.getParent()).resolve("settings.xml");
      authorization = "Basic " + encodeCredentialsFromSettings(repoId, settings, props);
    } else {
      authorization = "Basic " + encodeCredentials(username, password);
    }

    return authorization;
  }

  private static String encodeCredentials(String username, String password) {
    return Base64.getEncoder()
        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static String encodeCredentialsFromSettings(String id, Path settings, Properties props) {
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
      final var username = unwrap(xPath.evaluate("username", server), props);
      final var password = unwrap(xPath.evaluate("password", server), props);

      return encodeCredentials(username, password);
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse " + settings, e);
    }
  }

  static String unwrap(String value, Properties props) {
    if (value != null && value.startsWith("${env.") && value.endsWith("}")) {
      final var varName = value.substring(6, value.length() - 1);
      final var envValue = getProperty(props, varName);
      if (envValue == null || envValue.isEmpty()) {
        throw new IllegalStateException(
            "Environment variable is missing or invalid (name=" + varName + ")");
      }
      return envValue;
    }
    return value;
  }
}
