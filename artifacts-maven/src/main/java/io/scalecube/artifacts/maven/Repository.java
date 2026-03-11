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
    String id, String url, String authz, File repoDir, UpdatePolicy repoUpdatePolicy) {

  public static Repository newInstance(Properties props) {
    final var id = repoId(props);
    final var repoDir = repoDir(props);
    final var url = repoUrl(props);
    final var authorization = repoAuthorization(props, repoDir, id);
    final var updatePolicy = repoUpdatePolicy(props);

    return new Repository(id, url, authorization, repoDir, updatePolicy);
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

  private static File repoDir(Properties props) {
    final var dir = props.getProperty("scalecube.artifacts.maven.repo.dir");
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
    final var id = props.getProperty("scalecube.artifacts.maven.repo.id");
    if (id == null || id.isEmpty()) {
      throw new IllegalArgumentException("repository id is missing or invalid");
    }
    return id;
  }

  private static String repoUrl(Properties props) {
    final var url = props.getProperty("scalecube.artifacts.maven.repo.url");
    if (url == null || url.isEmpty()) {
      throw new IllegalArgumentException("repository url is missing or invalid");
    }
    return url;
  }

  private static UpdatePolicy repoUpdatePolicy(Properties props) {
    final var value = props.getProperty("scalecube.artifacts.maven.repo.updatePolicy");
    return value != null ? UpdatePolicy.valueOf(value.toUpperCase()) : UpdatePolicy.REMOTE;
  }

  private static String repoAuthorization(Properties props, File repoDir, String repoId) {
    final var username = props.getProperty("scalecube.artifacts.maven.repo.username");
    final var password = props.getProperty("scalecube.artifacts.maven.repo.password");

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

  private static String unwrap(String value, Properties props) {
    if (value != null && value.startsWith("${env.") && value.endsWith("}")) {
      final var varName = value.substring(6, value.length() - 1);
      final var envValue = props.getProperty(varName);
      if (envValue == null || envValue.isEmpty()) {
        throw new IllegalStateException(
            "Environment variable is missing or invalid (name=" + varName + ")");
      }
      return envValue;
    }
    return value;
  }
}
