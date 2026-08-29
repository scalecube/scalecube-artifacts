# scalecube-artifacts

Lightweight Java library for resolving and syncing artifacts from remote Maven repositories
(e.g. GitHub Packages) to the local filesystem, with support for SNAPSHOT timestamp resolution,
SHA-1 checksum validation, and configurable update policies.

## Requirements

* Java 11+
* No external runtime dependencies

## Features

- Resolves release and SNAPSHOT artifacts (including correct timestamped filenames for SNAPSHOTs)
- Honours Maven's `localCopy` rule: a SNAPSHOT installed by `mvn install` wins over any remote build
- Async HTTP/2 downloads via Java 11 `HttpClient`
- SHA-1 validation after every download, and again before serving a cached file
- Concurrency-safe: every published file is a single atomic rename, so parallel resolutions of the
  same coordinate never see a partial file
- Local cache awareness with configurable update policy (`REMOTE` vs `LOCAL`)
- Automatic retry with exponential back-off, covering both metadata and jar
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

A SNAPSHOT coordinate resolves to the latest timestamped build named by the remote metadata, and the
returned path is that timestamped file:

```java
// Downloads and returns bar-1.0-20250309.141500-23.jar
Path jar = resolver.resolve("com.example:bar:1.0-SNAPSHOT").join();
```

The base-named `bar-1.0-SNAPSHOT.jar` is never written. That name belongs to `mvn install`, and
writing it overwrote locally built jars and made two concurrent resolutions of the same coordinate
race on one path.

If `mvn install` has put `bar-1.0-SNAPSHOT.jar` in the local repository, marked
`<localCopy>true</localCopy>` in `maven-metadata-local.xml`, resolution returns that file and does
not use the network. This is Maven's own precedence rule:

```java
// -> ~/.m2/repository/com/example/bar/1.0-SNAPSHOT/bar-1.0-SNAPSHOT.jar, no HTTP request
Path jar = resolver.resolve("com.example:bar:1.0-SNAPSHOT").join();
```

## Configuration

All settings are passed as `java.util.Properties` to `ArtifactResolverProvider.create()`. Any
property set to `@null` is treated as not set (default applies), so a layered or templated config
can unset an inherited value.

### Repository

- `scalecube.artifacts.maven.repo.id` `(string: <required>)` – Repository identifier.
  Must match the `<id>` in `~/.m2/settings.xml` when credentials are read from there.

- `scalecube.artifacts.maven.repo.url` `(string: <required>)` – Base URL of the remote
  repository, no trailing slash.

- `scalecube.artifacts.maven.repo.dir` `(string: "~/.m2/repository")` – Local cache
  directory. Artifacts are stored under the standard Maven layout
  (`<groupId>/<artifactId>/<version>/`).

- `scalecube.artifacts.maven.repo.settings` `(string: "~/.m2/settings.xml")` – Where to read
  `<server>` credentials from. Independent of `repo.dir`, so pointing the cache at a scratch
  directory does not move the settings lookup with it.

- `scalecube.artifacts.maven.repo.updatePolicy` `(string: "REMOTE")` – Controls when the
  remote is consulted. `REMOTE` prefers a locally installed SNAPSHOT, and otherwise fetches
  metadata to learn which build to serve or download. `LOCAL` never touches the network: it
  serves the locally installed build, else the newest build already downloaded, and throws if
  there is neither.

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

A missing settings file, or one with no matching `<server>`, is not an error: requests are made
anonymously, which is correct for a public repository and harmless for an artifact already in the
local cache. A `<server>` whose `${env.VAR}` placeholder cannot be expanded *is* an error, since
that is a misconfiguration rather than an absent credential.

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
