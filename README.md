# scalecube-artifacts

Lightweight Java library for resolving and syncing artifacts from remote Maven repositories
(e.g. GitHub Packages) to the local filesystem, with support for SNAPSHOT timestamp resolution,
SHA-1 checksum validation, and configurable update policies.

## Requirements

* Java 11+
* No external runtime dependencies

## Features

- Resolves release and SNAPSHOT artifacts (including correct timestamped filenames for SNAPSHOTs)
- Async HTTP/2 downloads via Java 11 `HttpClient`
- SHA-1 checksum validation after every download
- Local cache awareness with configurable update policy (`REMOTE` vs `LOCAL`)
- Automatic retry with exponential back-off
- Credentials from inline properties or `~/.m2/settings.xml` (with `${env.VAR}` interpolation)
- Zero runtime dependencies beyond the JDK

## Use case

Ideal for CI/CD pipelines, custom build tools, offline mirrors, or any application that needs to
reliably fetch and cache Maven artifacts without pulling in a full Maven/Gradle runtime.

## Usage

### Maven dependency

Add `scalecube-artifacts-maven` (brings in `scalecube-artifacts-api` transitively):

```xml
<dependency>
  <groupId>io.scalecube</groupId>
  <artifactId>scalecube-artifacts-maven</artifactId>
  <version>0.1.3</version>
</dependency>
```

### Resolving an artifact

Create a resolver via `MavenResolverProvider` and call `resolve(spec)` with a
`groupId:artifactId:version` coordinate:

```java
import io.scalecube.artifacts.maven.MavenResolverProvider;
import java.util.Properties;

Properties props = new Properties();
props.setProperty("scalecube.artifacts.maven.repo.id",  "github");
props.setProperty("scalecube.artifacts.maven.repo.url", "https://maven.pkg.github.com/my-org/my-repo");
props.setProperty("scalecube.artifacts.maven.repo.username", "my-user");
props.setProperty("scalecube.artifacts.maven.repo.password", System.getenv("GITHUB_TOKEN"));

var resolver = new MavenResolverProvider().create(props);

Path jar = resolver.resolve("com.example:my-lib:1.2.3").join();
// jar → ~/.m2/repository/com/example/my-lib/1.2.3/my-lib-1.2.3.jar
```

### Using the SPI via ServiceLoader

If you depend only on `scalecube-artifacts-api` and want to load the implementation at runtime:

```java
import io.scalecube.artifacts.api.ArtifactResolverProvider;
import java.util.ServiceLoader;

var provider = ServiceLoader.load(ArtifactResolverProvider.class)
    .findFirst()
    .orElseThrow();

var resolver = provider.create(props);
```

### SNAPSHOT resolution

SNAPSHOT coordinates are resolved to the latest timestamped version from remote metadata and
downloaded as both the timestamped file and a stable `-SNAPSHOT.jar` alias:

```java
// Downloads bar-1.0-20250309.141500-23.jar and aliases it to bar-1.0-SNAPSHOT.jar
Path jar = resolver.resolve("com.example:bar:1.0-SNAPSHOT").join();
```

## Configuration

All settings are passed as `java.util.Properties` to `ArtifactResolverProvider.create()`.

### Repository

- `scalecube.artifacts.maven.repo.id` `(string: <required>)` – Repository identifier.
  Must match the `<id>` in `~/.m2/settings.xml` when credentials are read from there.

- `scalecube.artifacts.maven.repo.url` `(string: <required>)` – Base URL of the remote
  repository, no trailing slash.

- `scalecube.artifacts.maven.repo.dir` `(string: "~/.m2/repository")` – Local cache
  directory. Artifacts are stored under the standard Maven layout
  (`<groupId>/<artifactId>/<version>/`).

- `scalecube.artifacts.maven.repo.updatePolicy` `(string: "REMOTE")` – Controls when the
  remote is consulted. `REMOTE` always fetches and validates metadata before serving from
  cache. `LOCAL` serves from the local cache only and throws if the artifact is absent.

### Retry

- `scalecube.artifacts.maven.repo.retryMaxAttempts` `(integer: 10)` – Maximum number of
  download attempts before the returned `CompletableFuture` fails.

- `scalecube.artifacts.maven.repo.retryInitialDelayMs` `(integer: 3000)` – Initial delay
  in milliseconds before the first retry. Each subsequent attempt doubles the delay
  (exponential back-off).

### Authentication

- `scalecube.artifacts.maven.repo.username` `(string: "")` – Username for HTTP Basic auth.
  If omitted together with `password`, credentials are looked up in `~/.m2/settings.xml`
  by the repo `id`.

- `scalecube.artifacts.maven.repo.password` `(string: "")` – Password or token for HTTP
  Basic auth. If omitted together with `username`, credentials are looked up in
  `~/.m2/settings.xml` by the repo `id`.

When credentials come from `settings.xml`, placeholders in the form `${env.VAR_NAME}` are
resolved from the same `Properties` object passed to the provider:

```xml
<server>
  <id>github</id>
  <username>${env.GITHUB_USER}</username>
  <password>${env.GITHUB_TOKEN}</password>
</server>
```

```java
props.setProperty("GITHUB_USER",  System.getenv("GITHUB_USER"));
props.setProperty("GITHUB_TOKEN", System.getenv("GITHUB_TOKEN"));
```
