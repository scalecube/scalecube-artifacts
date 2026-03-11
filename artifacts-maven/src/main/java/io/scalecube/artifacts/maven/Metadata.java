package io.scalecube.artifacts.maven;

import java.util.List;
import java.util.StringJoiner;

public class Metadata {

  private String groupId;
  private String artifactId;
  private String version;
  private Versioning versioning;

  public Metadata() {}

  public String groupId() {
    return groupId;
  }

  public Metadata groupId(String groupId) {
    this.groupId = groupId;
    return this;
  }

  public String artifactId() {
    return artifactId;
  }

  public Metadata artifactId(String artifactId) {
    this.artifactId = artifactId;
    return this;
  }

  public String version() {
    return version;
  }

  public Metadata version(String version) {
    this.version = version;
    return this;
  }

  public Versioning versioning() {
    return versioning;
  }

  public Metadata versioning(Versioning versioning) {
    this.versioning = versioning;
    return this;
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", Metadata.class.getSimpleName() + "[", "]")
        .add("groupId='" + groupId + "'")
        .add("artifactId='" + artifactId + "'")
        .add("version='" + version + "'")
        .add("versioning=" + versioning)
        .toString();
  }

  public static class Versioning {

    private String latest;
    private String release;
    private Snapshot snapshot;
    private String lastUpdated;
    private List<String> versions;
    private List<SnapshotVersion> snapshotVersions;

    public Versioning() {}

    public String latest() {
      return latest;
    }

    public Versioning latest(String latest) {
      this.latest = latest;
      return this;
    }

    public String release() {
      return release;
    }

    public Versioning release(String release) {
      this.release = release;
      return this;
    }

    public Snapshot snapshot() {
      return snapshot;
    }

    public Versioning snapshot(Snapshot snapshot) {
      this.snapshot = snapshot;
      return this;
    }

    public String lastUpdated() {
      return lastUpdated;
    }

    public Versioning lastUpdated(String lastUpdated) {
      this.lastUpdated = lastUpdated;
      return this;
    }

    public List<String> versions() {
      return versions;
    }

    public Versioning versions(List<String> versions) {
      this.versions = versions;
      return this;
    }

    public List<SnapshotVersion> snapshotVersions() {
      return snapshotVersions;
    }

    public Versioning snapshotVersions(List<SnapshotVersion> snapshotVersions) {
      this.snapshotVersions = snapshotVersions;
      return this;
    }

    @Override
    public String toString() {
      return new StringJoiner(", ", Versioning.class.getSimpleName() + "[", "]")
          .add("latest='" + latest + "'")
          .add("release='" + release + "'")
          .add("snapshot=" + snapshot)
          .add("lastUpdated='" + lastUpdated + "'")
          .add("versions=" + versions)
          .add("snapshotVersions=" + snapshotVersions)
          .toString();
    }
  }

  public static class Snapshot {

    private String timestamp;
    private String buildNumber;

    public Snapshot() {}

    public String timestamp() {
      return timestamp;
    }

    public Snapshot timestamp(String timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public String buildNumber() {
      return buildNumber;
    }

    public Snapshot buildNumber(String buildNumber) {
      this.buildNumber = buildNumber;
      return this;
    }

    @Override
    public String toString() {
      return new StringJoiner(", ", Snapshot.class.getSimpleName() + "[", "]")
          .add("timestamp='" + timestamp + "'")
          .add("buildNumber='" + buildNumber + "'")
          .toString();
    }
  }

  public static class SnapshotVersion {

    private String extension;
    private String classifier;
    private String value;
    private String updated;

    public SnapshotVersion() {}

    public String extension() {
      return extension;
    }

    public SnapshotVersion extension(String extension) {
      this.extension = extension;
      return this;
    }

    public String classifier() {
      return classifier;
    }

    public SnapshotVersion classifier(String classifier) {
      this.classifier = classifier;
      return this;
    }

    public String value() {
      return value;
    }

    public SnapshotVersion value(String value) {
      this.value = value;
      return this;
    }

    public String updated() {
      return updated;
    }

    public SnapshotVersion updated(String updated) {
      this.updated = updated;
      return this;
    }

    @Override
    public String toString() {
      return new StringJoiner(", ", SnapshotVersion.class.getSimpleName() + "[", "]")
          .add("extension='" + extension + "'")
          .add("classifier='" + classifier + "'")
          .add("value='" + value + "'")
          .add("updated='" + updated + "'")
          .toString();
    }
  }
}
