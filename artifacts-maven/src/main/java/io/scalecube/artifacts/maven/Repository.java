package io.scalecube.artifacts.maven;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;

public record Repository(
    String id,
    String url,
    String authz,
    File repoDir,
    UpdatePolicy repoUpdatePolicy,
    int retryMaxAttempts,
    long retryInitialDelayMs) {

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
        repoAuthorization(properties, id),
        repoDir,
        repoUpdatePolicy(properties),
        repoRetryMaxAttempts(properties),
        repoRetryInitialDelayMs(properties));
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
   * The {@code settings.xml} to read credentials from; independent of {@link #REPO_DIR_PROP_NAME}.
   */
  private static Path repoSettings(Properties properties) {
    final var settings = getProperty(properties, REPO_SETTINGS_PROP_NAME);
    if (settings == null || settings.isEmpty()) {
      return Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
    }
    return Path.of(settings);
  }

  /**
   * Resolves the {@code Authorization} header value, or {@code null} when no credentials are
   * configured.
   *
   * <p>Missing credentials are not an error here. A public repository needs none, an artifact
   * already in the local repository is served without a request at all, and when credentials really
   * are required the registry's {@code 401} names the problem far more precisely than a failure
   * raised before anything was attempted. This used to throw whenever {@code settings.xml} was
   * absent, which broke CI images that pass {@code --settings} elsewhere and any caller that points
   * {@link #REPO_DIR_PROP_NAME} at a scratch directory.
   */
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

  private static String credentialsFromSettings(
      String id, Path settings, Properties properties) {
    if (Files.notExists(settings)) {
      LOGGER.log(Level.DEBUG, () -> settings + " does not exist, continuing without credentials");
      return null;
    }

    try (var is = Files.newInputStream(settings)) {
      final var document = documentBuilder().parse(is);
      document.getDocumentElement().normalize();

      // Direct-child navigation rather than an XPath predicate built from `id`: the shape is fixed,
      // and a repository id must never be able to steer the query.
      for (final var server :
          children(child(document.getDocumentElement(), "servers"), "server")) {
        if (!id.equals(unwrap(text(server, "id"), properties))) {
          continue;
        }
        final var username = unwrap(text(server, "username"), properties);
        final var password = unwrap(text(server, "password"), properties);
        if (username == null || password == null) {
          LOGGER.log(
              Level.WARNING,
              () -> "Server id=" + id + " in " + settings + " has no usable credentials");
          return null;
        }
        return "Basic " + encodeCredentials(username, password);
      }

      LOGGER.log(Level.WARNING, () -> "No server found with id=" + id + " in " + settings);
      return null;
    } catch (IllegalStateException e) {
      // An unexpandable ${env.NAME}: a misconfiguration worth failing on, see unwrap(...).
      throw e;
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to parse " + settings, e);
      return null;
    }
  }

  /**
   * Expands a {@code ${env.NAME}} placeholder from the supplied properties.
   *
   * <p>An unexpandable placeholder is a misconfiguration, not an absent credential: the caller went
   * to the trouble of declaring the server, so failing here - naming the variable - beats sending
   * {@code ${env.GITHUB_TOKEN}} as a literal password and reporting whatever the registry says
   * about it. Contrast with a missing {@code settings.xml} or a missing {@code <server>}, which
   * simply mean "no credentials configured"; see {@link #repoAuthorization}.
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

  private static List<Element> children(Element parent, String name) {
    final var result = new ArrayList<Element>();
    if (parent == null) {
      return result;
    }
    final var nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element element && name.equals(localName(element))) {
        result.add(element);
      }
    }
    return result;
  }

  private static Element child(Element parent, String name) {
    final var children = children(parent, name);
    return children.isEmpty() ? null : children.get(0);
  }

  private static String text(Element parent, String name) {
    final var child = child(parent, name);
    if (child == null) {
      return null;
    }
    final var text = child.getTextContent();
    return text == null || text.isBlank() ? null : text.trim();
  }

  private static String localName(Element element) {
    final var tagName = element.getTagName();
    final var colon = tagName.indexOf(':');
    return colon < 0 ? tagName : tagName.substring(colon + 1);
  }

  /** {@code settings.xml} comes from outside the JVM, so resolve nothing external. */
  private static DocumentBuilder documentBuilder() throws ParserConfigurationException {
    final var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setExpandEntityReferences(false);
    factory.setNamespaceAware(false);
    factory.setValidating(false);
    return factory.newDocumentBuilder();
  }
}
