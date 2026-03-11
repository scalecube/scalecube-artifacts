package io.scalecube.artifacts.maven;

import java.io.InputStream;
import java.util.ArrayList;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

public class MetadataParser {

  private MetadataParser() {
    // Do not instantiate
  }

  public static Metadata parseMetadata(InputStream in) throws XMLStreamException {
    final var factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

    final var reader = factory.createXMLStreamReader(in);

    final var metadata = new Metadata();
    Metadata.SnapshotVersion snapshotVersion = null;

    String currentElement = null;

    while (reader.hasNext()) {
      int event = reader.next();

      switch (event) {
        case XMLStreamConstants.START_ELEMENT:
          currentElement = reader.getLocalName();

          switch (currentElement) {
            case "versioning":
              metadata.versioning(new Metadata.Versioning());
              break;

            case "versions":
              metadata.versioning().versions(new ArrayList<>());
              break;

            case "snapshot":
              metadata.versioning().snapshot(new Metadata.Snapshot());
              break;

            case "snapshotVersions":
              metadata.versioning().snapshotVersions(new ArrayList<>());
              break;

            case "snapshotVersion":
              snapshotVersion = new Metadata.SnapshotVersion();
              break;
          }
          break;

        case XMLStreamConstants.CHARACTERS:
          if (reader.isWhiteSpace() || currentElement == null) {
            break;
          }

          String text = reader.getText().trim();
          if (text.isEmpty()) {
            break;
          }

          switch (currentElement) {
            case "groupId":
              metadata.groupId(text);
              break;
            case "artifactId":
              metadata.artifactId(text);
              break;
            case "version":
              final var versioning = metadata.versioning();
              if (versioning == null) {
                // <version> outside of <versioning> => top-level metadata.version (snapshot case)
                metadata.version(text);
              } else {
                final var versions = versioning.versions();
                if (versions != null) {
                  // <version> inside <versions> => adds to list
                  versions.add(text);
                }
              }
              break;

            case "latest":
              metadata.versioning().latest(text);
              break;
            case "release":
              metadata.versioning().release(text);
              break;
            case "lastUpdated":
              metadata.versioning().lastUpdated(text);
              break;

            case "timestamp":
              metadata.versioning().snapshot().timestamp(text);
              break;

            case "buildNumber":
              metadata.versioning().snapshot().buildNumber(text);
              break;

            case "extension":
              if (snapshotVersion != null) {
                snapshotVersion.extension(text);
              }
              break;

            case "classifier":
              if (snapshotVersion != null) {
                snapshotVersion.classifier(text);
              }
              break;

            case "value":
              if (snapshotVersion != null) {
                snapshotVersion.value(text);
              }
              break;

            case "updated":
              if (snapshotVersion != null) {
                snapshotVersion.updated(text);
              }
              break;
          }
          break;

        case XMLStreamConstants.END_ELEMENT:
          String end = reader.getLocalName();
          if ("snapshotVersion".equals(end) && snapshotVersion != null) {
            metadata.versioning().snapshotVersions().add(snapshotVersion);
            snapshotVersion = null;
          }
          currentElement = null;
          break;
      }
    }

    return metadata;
  }
}
