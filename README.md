# scalecube-artifacts

Lightweight Java library for resolving and syncing artifacts from remote Maven repositories
(e.g. GitHub Packages) to the local filesystem, with support for SNAPSHOT timestamp resolution,
SHA-1 checksum validation, and configurable update policies.

## Requirements

* Java 17+
* Linux, macOS and Windows — all three are built and tested in CI
* No external runtime dependencies

## Features

- Resolves release and SNAPSHOT artifacts (including correct timestamped filenames for SNAPSHOTs)
- Two non-overlapping update policies: `REMOTE` always goes to the remote, `LOCAL` never does
- Async HTTP/2 downloads via `java.net.http.HttpClient`
- SHA-1 validation after every download, and before serving a cached file (can be turned off)
- Concurrency-safe: publishing is idempotent. A build already present with the same content is
  never rewritten, and anything else is published with a single atomic move, so parallel
  resolutions of the same coordinate never see a partial file. This matters beyond tidiness on
  Windows, where replacing a file another thread holds open is refused
- Never writes the base-named `<artifactId>-<version>.jar`, so `mvn install` output is never
  overwritten
- Automatic retry with exponential back-off, covering both metadata and jar
- Credentials from inline properties or `~/.m2/settings.xml` (with `${env.VAR}` interpolation)
- Zero runtime dependencies beyond the JDK

## Use case

Ideal for CI/CD pipelines, custom build tools, integration-test harnesses, or any application that
needs to reliably fetch and cache Maven artifacts without pulling in a full Maven/Gradle runtime.

## Usage

### Maven dependency

Add `scalecube-artifacts-maven` (brings in `scalecube-artifacts-api` transitively):

```xml
<dependency>
  <groupId>io.scalecube</groupId>
  <artifactId>scalecube-artifacts-maven</artifactId>
  <version>0.1.4</version>
</dependency>
```

### Resolving an artifact

Create a resolver via `MavenResolverProvider` and call `resolve(spec)` with a
`groupId:artifactId:version` coordinate:

```java
import io.scalecube.artifacts.maven.MavenResolverProvider;
import java.util.Properties;

Properties props = new Properties();
props.setProperty("scalecube.artifacts.maven.repo.id", "github");
props.setProperty("scalecube.artifacts.maven.repo.url", "https://maven.pkg.github.com/my-org/my-repo");
props.setProperty("scalecube.artifacts.maven.repo.username", "my-user");
props.setProperty("scalecube.artifacts.maven.repo.password", System.getenv("GITHUB_TOKEN"));

var resolver = new MavenResolverProvider().create(props);

Path jar = resolver.resolve("com.example:my-lib:1.2.3").join();
// jar -> ~/.m2/repository/com/example/my-lib/1.2.3/my-lib-1.2.3.jar
```

`resolve` returns a `CompletableFuture<Path>`; a bad coordinate or a failed download completes it
exceptionally rather than throwing from the call itself.

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

A build installed by `mvn install` is **not** picked up automatically. Under the default `REMOTE`
policy the remote is always consulted, whether or not a local build is present. To run against your
own build, ask for it explicitly with `updatePolicy=LOCAL`:

```java
props.setProperty("scalecube.artifacts.maven.repo.updatePolicy", "LOCAL");

// -> ~/.m2/repository/com/example/bar/1.0-SNAPSHOT/bar-1.0-SNAPSHOT.jar, no HTTP request
Path jar = resolver.resolve("com.example:bar:1.0-SNAPSHOT").join();
```

Making it explicit is deliberate. The presence of a locally installed jar says nothing about which
branch it was built from or how old it is, so letting it win silently makes resolution
unpredictable. Setting the policy is a statement that you know which build is there.

## Configuration

All settings are passed as `java.util.Properties` to `ArtifactResolverProvider.create()`. Any
property set to `@null` is treated as not set (default applies), so a layered or templated config
can unset an inherited value.

### Repository

- `scalecube.artifacts.maven.repo.id` `(string: <required>)` – Repository identifier.
  Must match the `<id>` in `~/.m2/settings.xml` when credentials are read from there.

- `scalecube.artifacts.maven.repo.url` `(string: <required>)` – Base URL of the remote
  repository. A trailing slash is trimmed.

- `scalecube.artifacts.maven.repo.dir` `(string: "~/.m2/repository")` – Local cache
  directory. Artifacts are stored under the standard Maven layout
  (`<groupId>/<artifactId>/<version>/`).

- `scalecube.artifacts.maven.repo.settings` `(string: "~/.m2/settings.xml")` – Where to read
  `<server>` credentials from. Independent of `repo.dir`, so pointing the cache at a scratch
  directory does not move the settings lookup with it.

- `scalecube.artifacts.maven.repo.verifyCachedChecksum` `(boolean: true)` – Whether a jar
  already in the local cache is checked against its stored `.sha1` before being served. A jar with
  no `.sha1` beside it, or one that disagrees, is downloaded again. Checking costs one pass over
  the file, so it can be turned off for large artifacts.

- `scalecube.artifacts.maven.repo.updatePolicy` `(string: "REMOTE")` – Controls when the
  remote is consulted. The two policies do not overlap.

  `REMOTE` always checks remote metadata and downloads when the remote is newer. Whether a build
  installed by `mvn install` is present is irrelevant under this policy. A previously downloaded
  build of the *same* timestamped name is still served from the cache rather than re-downloaded —
  the file name identifies the build, so only its checksum needs checking.

  `LOCAL` never touches the network. For a SNAPSHOT it serves the build installed by `mvn install`
  — the base-named jar, with `<localCopy>true</localCopy>` in `maven-metadata-local.xml` — and for
  a release the jar at its canonical name. It throws `IllegalStateException` if there is none. A
  SNAPSHOT that was merely downloaded earlier does not count: setting `LOCAL` is a statement that
  the artifact is present locally, so it fails loudly rather than recovering from the remote.

  Because each resolver is built from its own `Properties`, the policy is per-artifact: one
  artifact can be pinned to `LOCAL` while everything else stays on `REMOTE`.

### Retry

Two retry ladders apply, and both use the settings below. `Fetcher` retries an individual request on
a network error or a retryable status (429, 502, 503, 504). `MavenResolver` retries the whole
metadata-then-jar chain on 404, because a freshly published artifact answers 404 on its metadata
before its jar appears.

- `scalecube.artifacts.maven.repo.retryMaxAttempts` `(integer: 10)` – Total attempts, including
  the first, before the returned `CompletableFuture` fails.

- `scalecube.artifacts.maven.repo.retryInitialDelayMs` `(integer: 3000)` – Delay in milliseconds
  before the first retry. Each subsequent attempt doubles the delay (exponential back-off).

- `scalecube.artifacts.maven.repo.retryMaxDelayMs` `(integer: 60000)` – Ceiling for the doubling
  delay, in milliseconds. Without it a long retry run would keep doubling until it slept for hours.

### Authentication

- `scalecube.artifacts.maven.repo.username` `(string: unset)` – Username for HTTP Basic auth.

- `scalecube.artifacts.maven.repo.password` `(string: unset)` – Password or token for HTTP
  Basic auth.

Both must be set and non-empty to be used. Otherwise credentials are looked up in the settings file
by the repo `id`.

A missing settings file, or one with no matching `<server>`, is not an error: requests are made
anonymously, which is correct for a public repository and harmless for an artifact already in the
local cache. A `<server>` whose `${env.VAR}` placeholder cannot be expanded *is* an error, since
that is a misconfiguration rather than an absent credential.

When credentials come from `settings.xml`, placeholders in the form `${env.VAR_NAME}` are
resolved from the same `Properties` object passed to the provider — not from the process
environment:

```xml
<server>
  <id>github</id>
  <username>${env.GITHUB_USER}</username>
  <password>${env.GITHUB_TOKEN}</password>
</server>
```

```java
props.setProperty("GITHUB_USER", System.getenv("GITHUB_USER"));
props.setProperty("GITHUB_TOKEN", System.getenv("GITHUB_TOKEN"));
```
