package io.scalecube.artifacts.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.scalecube.artifacts.maven.Metadata.Snapshot;
import io.scalecube.artifacts.maven.Metadata.SnapshotVersion;
import io.scalecube.artifacts.maven.Metadata.Versioning;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MetadataParserTest {

  @MethodSource("parseMetadataSource")
  @ParameterizedTest(name = "{0}")
  void parseMetadata(String test, String input, Supplier<Metadata> supplier)
      throws XMLStreamException {
    final var actual =
        MetadataParser.parseMetadata(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    final var expected = supplier.get();

    assertEquals(expected.groupId(), actual.groupId(), "groupId");
    assertEquals(expected.artifactId(), actual.artifactId(), "artifactId");
    assertEquals(expected.version(), actual.version(), "version");

    assertVersioning(expected.versioning(), actual.versioning());
  }

  private static void assertVersioning(Versioning expected, Versioning actual) {
    assertEquals(expected.latest(), actual.latest(), "latest");
    assertEquals(expected.release(), actual.release(), "release");
    assertEquals(expected.lastUpdated(), actual.lastUpdated(), "lastUpdated");
    assertEquals(expected.versions(), actual.versions(), "versions");
    assertSnapshot(expected.snapshot(), actual.snapshot());
    assertSnapshotVersions(expected.snapshotVersions(), actual.snapshotVersions());
  }

  private static void assertSnapshotVersions(
      List<SnapshotVersion> expected, List<SnapshotVersion> actual) {
    if (expected == null) {
      assertNull(actual);
      return;
    }
    assertEquals(expected.size(), actual.size(), "snapshotVersions.size");
    for (int i = 0; i < expected.size(); i++) {
      assertSnapshotVersion(expected.get(i), actual.get(i));
    }
  }

  private static void assertSnapshotVersion(SnapshotVersion expected, SnapshotVersion actual) {
    if (expected == null) {
      assertNull(actual);
      return;
    }
    assertEquals(expected.extension(), actual.extension(), "extension");
    assertEquals(expected.classifier(), actual.classifier(), "classifier");
    assertEquals(expected.value(), actual.value(), "value");
    assertEquals(expected.updated(), actual.updated(), "updated");
  }

  private static void assertSnapshot(Snapshot expected, Snapshot actual) {
    if (expected == null) {
      assertNull(actual);
      return;
    }
    assertEquals(expected.timestamp(), actual.timestamp(), "timestamp");
    assertEquals(expected.buildNumber(), actual.buildNumber(), "buildNumber");
  }

  private static Stream<Arguments> parseMetadataSource() {
    final var builder = Stream.<Arguments>builder();

    builder.add(
        arguments(
            "Test#1 - standard GA-level metadata with versions list",
            """
        <metadata>
          <groupId>com.example</groupId>
          <artifactId>lib</artifactId>
          <versioning>
            <latest>1.2.3</latest>
            <release>1.2.3</release>
            <versions>
              <version>1.0.0</version>
              <version>1.1.0</version>
              <version>1.2.0</version>
              <version>1.2.3</version>
            </versions>
            <lastUpdated>20250215123456</lastUpdated>
          </versioning>
        </metadata>
        """,
            (Supplier<Object>)
                () ->
                    new Metadata()
                        .groupId("com.example")
                        .artifactId("lib")
                        .versioning(
                            new Versioning()
                                .latest("1.2.3")
                                .release("1.2.3")
                                .versions(List.of("1.0.0", "1.1.0", "1.2.0", "1.2.3"))
                                .lastUpdated("20250215123456"))));

    builder.add(
        arguments(
            "Test#2 - snapshot-level metadata (typical SNAPSHOT resolution)",
            """
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>my-service</artifactId>
              <version>2.1.0-SNAPSHOT</version>
              <versioning>
                <snapshot>
                  <timestamp>20260225.142030</timestamp>
                  <buildNumber>45</buildNumber>
                </snapshot>
                <lastUpdated>20260225142045</lastUpdated>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>2.1.0-20260225.142030-45</value>
                    <updated>20260225142030</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <classifier>sources</classifier>
                    <value>2.1.0-20260225.142030-45</value>
                    <updated>20260225142030</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <extension>pom</extension>
                    <value>2.1.0-20260225.142030-45</value>
                    <updated>20260225142030</updated>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
            """,
            (Supplier<Metadata>)
                () ->
                    new Metadata()
                        .groupId("com.example")
                        .artifactId("my-service")
                        .version("2.1.0-SNAPSHOT")
                        .versioning(
                            new Metadata.Versioning()
                                .snapshot(
                                    new Metadata.Snapshot()
                                        .timestamp("20260225.142030")
                                        .buildNumber("45"))
                                .lastUpdated("20260225142045")
                                .snapshotVersions(
                                    List.of(
                                        new Metadata.SnapshotVersion()
                                            .extension("jar")
                                            .value("2.1.0-20260225.142030-45")
                                            .updated("20260225142030"),
                                        new Metadata.SnapshotVersion()
                                            .extension("jar")
                                            .classifier("sources")
                                            .value("2.1.0-20260225.142030-45")
                                            .updated("20260225142030"),
                                        new Metadata.SnapshotVersion()
                                            .extension("pom")
                                            .value("2.1.0-20260225.142030-45")
                                            .updated("20260225142030"))))));

    builder.add(
        arguments(
            "Test#3 - minimal release metadata (only latest + lastUpdated)",
            """
            <metadata>
              <groupId>org.junit.jupiter</groupId>
              <artifactId>junit-jupiter</artifactId>
              <versioning>
                <latest>5.11.3</latest>
                <lastUpdated>20260210123456</lastUpdated>
              </versioning>
            </metadata>
            """,
            (Supplier<Metadata>)
                () ->
                    new Metadata()
                        .groupId("org.junit.jupiter")
                        .artifactId("junit-jupiter")
                        .versioning(
                            new Metadata.Versioning()
                                .latest("5.11.3")
                                .lastUpdated("20260210123456"))));

    builder.add(
        arguments(
            "Test#4 - empty versions list",
            """
            <metadata>
              <groupId>com.mycompany</groupId>
              <artifactId>internal-lib</artifactId>
              <versioning>
                <latest>3.0.0</latest>
                <release>3.0.0</release>
                <versions/>
                <lastUpdated>20260101120000</lastUpdated>
              </versioning>
            </metadata>
            """,
            (Supplier<Metadata>)
                () ->
                    new Metadata()
                        .groupId("com.mycompany")
                        .artifactId("internal-lib")
                        .versioning(
                            new Metadata.Versioning()
                                .latest("3.0.0")
                                .release("3.0.0")
                                .versions(List.of()) // or null — decide in model
                                .lastUpdated("20260101120000"))));

    builder.add(
        arguments(
            "Test#5 - mixed order of <latest>, <versions>, <release> inside versioning",
            """
            <metadata>
              <groupId>net.example</groupId>
              <artifactId>lib-a</artifactId>
              <versioning>
                <latest>4.2.1</latest>
                <versions>
                  <version>4.1.0</version>
                  <version>4.2.0</version>
                  <version>4.2.1</version>
                </versions>
                <release>4.2.1</release>
                <lastUpdated>20260226093000</lastUpdated>
              </versioning>
            </metadata>
            """,
            (Supplier<Metadata>)
                () ->
                    new Metadata()
                        .groupId("net.example")
                        .artifactId("lib-a")
                        .versioning(
                            new Metadata.Versioning()
                                .latest("4.2.1")
                                .versions(List.of("4.1.0", "4.2.0", "4.2.1"))
                                .release("4.2.1")
                                .lastUpdated("20260226093000"))));

    builder.add(
        arguments(
            "Test#6 - snapshotVersions before snapshot",
            """
            <metadata>
              <groupId>com.beta</groupId>
              <artifactId>app</artifactId>
              <version>5.0-SNAPSHOT</version>
              <versioning>
                <snapshotVersions>
                  <snapshotVersion>
                    <extension>jar</extension>
                    <value>5.0-20260227.160000-8</value>
                    <updated>20260227160000</updated>
                  </snapshotVersion>
                </snapshotVersions>
                <snapshot>
                  <timestamp>20260227.160000</timestamp>
                  <buildNumber>8</buildNumber>
                </snapshot>
                <lastUpdated>20260227160000</lastUpdated>
              </versioning>
            </metadata>
            """,
            (Supplier<Metadata>)
                () ->
                    new Metadata()
                        .groupId("com.beta")
                        .artifactId("app")
                        .version("5.0-SNAPSHOT")
                        .versioning(
                            new Metadata.Versioning()
                                .snapshotVersions(
                                    List.of(
                                        new Metadata.SnapshotVersion()
                                            .extension("jar")
                                            .value("5.0-20260227.160000-8")
                                            .updated("20260227160000")))
                                .snapshot(
                                    new Metadata.Snapshot()
                                        .timestamp("20260227.160000")
                                        .buildNumber("8"))
                                .lastUpdated("20260227160000"))));

    return builder.build();
  }
}
