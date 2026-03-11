# ScaleCube Artifacts

Lightweight Java library for resolving and syncing artifacts from remote repositories (e.g.
GitHub Packages) to local system with support for SNAPSHOT timestamp resolution, checksum
validation, and configurable update policies.

# Requirements

* Java 11+
* No external runtime dependencies

# Features

- Resolves release and SNAPSHOT artifacts (including correct timestamped filenames for SNAPSHOTs)
- Async HTTP downloads
- SHA-1 checksum validation after download
- Local cache awareness + configurable update policy (`REMOTE` vs `LOCAL`)
- No external dependencies beyond Java 11+ standard library + minimal test deps

# Use case

Ideal for CI/CD pipelines, custom build tools, offline mirrors, or any application that needs to
reliably fetch and cache Maven artifacts without relying on full Maven/Gradle.
