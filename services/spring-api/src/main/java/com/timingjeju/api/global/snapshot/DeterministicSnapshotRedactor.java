package com.timingjeju.api.global.snapshot;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotRedactionResult;
import com.timingjeju.api.application.snapshot.SnapshotRedactor;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreError;
import com.timingjeju.api.application.snapshot.SnapshotStoreException;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class DeterministicSnapshotRedactor implements SnapshotRedactor {
  private static final String REDACTED = "[REDACTED]";
  private static final Pattern TEXT_FIELD =
      Pattern.compile("(?i)([a-z][a-z0-9_.-]*)(\\s*[=:]\\s*)([^&\\r\\n]+)");
  private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s,;]+");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern URL =
      Pattern.compile("(?i)https?://[^\\s,;]+", Pattern.UNICODE_CASE);

  private final ObjectMapper objectMapper;

  public DeterministicSnapshotRedactor(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String version() {
    return "snapshot-redaction-v1";
  }

  @Override
  public SnapshotRedactionResult redact(
      SnapshotPayloadFormat format, String charset, byte[] payload, Map<String, Object> metadata) {
    String metadataJson = writeJson(redactValue(metadata));
    if (format == SnapshotPayloadFormat.BINARY) {
      return new SnapshotRedactionResult(
          null, metadataJson, SnapshotStatus.IGNORED, "SNAPSHOT_BINARY_PAYLOAD");
    }
    String decoded = decodeUtf8(charset, payload);
    try {
      if (decoded == null) {
        throw new IllegalArgumentException("invalid UTF-8");
      }
      String payloadJson =
          switch (format) {
            case JSON -> writeJson(redactValue(readJson(decoded)));
            case XML -> writeJson(Map.of("body", redactXml(payload), "contentType", "xml"));
            case TEXT -> writeJson(Map.of("body", redactText(decoded), "contentType", "text"));
            case BINARY -> throw new IllegalStateException("unreachable");
          };
      return new SnapshotRedactionResult(payloadJson, metadataJson, SnapshotStatus.RECEIVED, null);
    } catch (RuntimeException exception) {
      return new SnapshotRedactionResult(
          null, metadataJson, SnapshotStatus.REJECTED, "SNAPSHOT_MALFORMED_PAYLOAD");
    }
  }

  private static String decodeUtf8(String charset, byte[] payload) {
    if (charset == null || !"UTF-8".equalsIgnoreCase(charset.strip())) {
      throw SnapshotStoreException.of(SnapshotStoreError.UNSUPPORTED_CHARSET);
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(payload))
          .toString();
    } catch (CharacterCodingException exception) {
      return null;
    }
  }

  private Object readJson(String decoded) {
    return objectMapper.readValue(decoded, new TypeReference<Object>() {});
  }

  private Object redactValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      List<Map.Entry<String, Object>> entries = new ArrayList<>();
      map.forEach(
          (key, item) ->
              entries.add(
                  new java.util.AbstractMap.SimpleImmutableEntry<>(String.valueOf(key), item)));
      entries.sort(Map.Entry.comparingByKey());
      Map<String, Object> result = new LinkedHashMap<>();
      entries.forEach(
          entry ->
              result.put(
                  entry.getKey(),
                  SnapshotSensitiveFieldRegistry.isSensitive(entry.getKey())
                      ? REDACTED
                      : redactValue(entry.getValue())));
      return result;
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> result = new ArrayList<>();
      iterable.forEach(item -> result.add(redactValue(item)));
      return result;
    }
    if (value instanceof String text) {
      return redactText(text);
    }
    if (value == null || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    return REDACTED;
  }

  private String redactXml(byte[] payload) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(payload));
      redactNode(document.getDocumentElement());
      TransformerFactory transformers = TransformerFactory.newInstance();
      transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = transformers.newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      transformer.setOutputProperty(OutputKeys.INDENT, "no");
      StringWriter output = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(output));
      return output.toString();
    } catch (Exception exception) {
      throw new IllegalArgumentException("malformed XML");
    }
  }

  private void redactNode(Element element) {
    var attributes = element.getAttributes();
    for (int index = 0; index < attributes.getLength(); index++) {
      Node attribute = attributes.item(index);
      attribute.setNodeValue(
          SnapshotSensitiveFieldRegistry.isSensitive(attribute.getNodeName())
              ? REDACTED
              : redactText(attribute.getNodeValue()));
    }
    if (SnapshotSensitiveFieldRegistry.isSensitive(element.getTagName())) {
      element.setTextContent(REDACTED);
      return;
    }
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element childElement) {
        redactNode(childElement);
      } else if (child.getNodeType() == Node.TEXT_NODE) {
        child.setNodeValue(redactText(child.getNodeValue()));
      }
    }
  }

  private static String redactText(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = TEXT_FIELD.matcher(text);
    StringBuffer output = new StringBuffer();
    while (matcher.find()) {
      String replacement =
          SnapshotSensitiveFieldRegistry.isSensitive(matcher.group(1))
              ? matcher.group(1) + matcher.group(2) + REDACTED
              : matcher.group();
      matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(output);
    String withoutUrls = URL.matcher(output.toString()).replaceAll(REDACTED);
    return EMAIL.matcher(BEARER.matcher(withoutUrls).replaceAll(REDACTED)).replaceAll(REDACTED);
  }

  private String writeJson(Object value) {
    return objectMapper.writeValueAsString(value);
  }
}
