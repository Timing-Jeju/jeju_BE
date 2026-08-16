package com.timingjeju.api.global.tago.stop;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.stop.TagoCityCode;
import com.timingjeju.api.application.tago.stop.TagoStation;
import com.timingjeju.api.application.tago.stop.TagoStationPage;
import com.timingjeju.api.application.tago.stop.TagoStopImportException;
import com.timingjeju.api.application.tago.stop.TagoStopPayloadParser;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class TagoStopParser implements TagoStopPayloadParser {
  private static final String SUCCESS_CODE = "00";
  private static final double MIN_LONGITUDE = 125.9;
  private static final double MAX_LONGITUDE = 127.0;
  private static final double MIN_LATITUDE = 33.0;
  private static final double MAX_LATITUDE = 34.0;
  private final ObjectReader jsonReader;

  public TagoStopParser(ObjectMapper objectMapper) {
    jsonReader =
        Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.")
            .reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  public List<TagoCityCode> parseCityCodes(SnapshotPayloadFormat format, byte[] payload) {
    Envelope envelope = envelope(format, payload);
    List<TagoCityCode> result = new ArrayList<>();
    Set<String> codes = new HashSet<>();
    for (Map<String, String> item : envelope.items()) {
      String code = required(item, "citycode");
      String name = required(item, "cityname");
      if (!code.matches("[0-9]{1,10}") || !codes.add(code)) {
        throw TagoStopImportException.invalidResponse();
      }
      result.add(new TagoCityCode(code, name));
    }
    if (result.isEmpty() || envelope.totalCount() != result.size()) {
      throw TagoStopImportException.invalidResponse();
    }
    return List.copyOf(result);
  }

  public String discoverJejuCityCode(SnapshotPayloadFormat format, byte[] payload) {
    List<TagoCityCode> matches =
        parseCityCodes(format, payload).stream()
            .filter(city -> city.name().contains("제주"))
            .toList();
    if (matches.size() != 1) {
      throw TagoStopImportException.jejuNotFound();
    }
    return matches.getFirst().code();
  }

  public TagoStationPage parseStations(
      SnapshotPayloadFormat format, byte[] payload, String expectedCityCode, int expectedPageNo) {
    if (expectedCityCode == null || expectedCityCode.isBlank() || expectedPageNo < 1) {
      throw TagoStopImportException.invalidRequest();
    }
    Envelope envelope = envelope(format, payload);
    if (envelope.pageNo() != expectedPageNo
        || envelope.numOfRows() < 1
        || envelope.totalCount() < 0) {
      throw TagoStopImportException.invalidResponse();
    }
    List<TagoStation> stations = new ArrayList<>();
    Set<String> naturalKeys = new HashSet<>();
    for (Map<String, String> item : envelope.items()) {
      String cityCode = required(item, "citycode");
      String nodeId = required(item, "nodeid");
      String nodeName = required(item, "nodenm");
      String nodeNo = optional(item, "nodeno");
      if (!cityCode.equals(expectedCityCode)
          || nodeId.length() > 512
          || nodeName.length() > 255
          || !naturalKeys.add(cityCode + '\u0000' + nodeId)) {
        throw TagoStopImportException.invalidResponse();
      }
      double latitude = coordinate(required(item, "gpslati"));
      double longitude = coordinate(required(item, "gpslong"));
      if (latitude < MIN_LATITUDE
          || latitude > MAX_LATITUDE
          || longitude < MIN_LONGITUDE
          || longitude > MAX_LONGITUDE) {
        throw TagoStopImportException.invalidResponse();
      }
      stations.add(new TagoStation(cityCode, nodeId, nodeNo, nodeName, longitude, latitude));
    }
    if (stations.size() > envelope.numOfRows()
        || (envelope.totalCount() > 0 && stations.isEmpty())
        || ((long) (envelope.pageNo() - 1) * envelope.numOfRows() + stations.size()
            > envelope.totalCount())) {
      throw TagoStopImportException.invalidResponse();
    }
    return new TagoStationPage(
        envelope.pageNo(), envelope.numOfRows(), envelope.totalCount(), stations);
  }

  private Envelope envelope(SnapshotPayloadFormat format, byte[] payload) {
    Objects.requireNonNull(format, "format은 필수입니다.");
    Objects.requireNonNull(payload, "payload는 필수입니다.");
    try {
      return switch (format) {
        case JSON -> jsonEnvelope(payload);
        case XML -> xmlEnvelope(payload);
        case TEXT, BINARY -> throw TagoStopImportException.invalidResponse();
      };
    } catch (TagoStopImportException failure) {
      throw failure;
    } catch (Exception ignored) {
      throw TagoStopImportException.invalidResponse();
    }
  }

  private Envelope jsonEnvelope(byte[] payload) {
    JsonNode root = jsonReader.readTree(payload);
    requireObjectWith(root, Set.of("response"));
    JsonNode response = requiredObject(root, "response");
    requireObjectWith(response, Set.of("header", "body"));
    JsonNode header = requiredObject(response, "header");
    requireObjectWith(header, Set.of("resultCode", "resultMsg"));
    requireText(header, "resultMsg");
    requireSuccess(requireText(header, "resultCode"));
    JsonNode body = requiredObject(response, "body");
    requireObjectWith(body, Set.of("items", "numOfRows", "pageNo", "totalCount"));
    JsonNode itemsNode = requiredObject(body, "items").path("item");
    List<Map<String, String>> items = new ArrayList<>();
    if (itemsNode.isArray()) {
      itemsNode.forEach(item -> items.add(jsonItem(item)));
    } else if (itemsNode.isObject()) {
      items.add(jsonItem(itemsNode));
    } else if (!itemsNode.isMissingNode() && !itemsNode.isNull()) {
      throw TagoStopImportException.invalidResponse();
    }
    return new Envelope(
        positiveOrZero(body, "pageNo"),
        positiveOrZero(body, "numOfRows"),
        positiveOrZero(body, "totalCount"),
        items);
  }

  private static Map<String, String> jsonItem(JsonNode item) {
    if (!item.isObject()) {
      throw TagoStopImportException.invalidResponse();
    }
    Map<String, String> fields = new LinkedHashMap<>();
    item.properties()
        .forEach(
            entry -> {
              if (!entry.getValue().isTextual()) {
                throw TagoStopImportException.invalidResponse();
              }
              fields.put(entry.getKey(), entry.getValue().asString());
            });
    return fields;
  }

  private static void requireObjectWith(JsonNode node, Set<String> required) {
    if (!node.isObject()
        || !node.properties().stream()
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet())
            .containsAll(required)) {
      throw TagoStopImportException.invalidResponse();
    }
  }

  private static JsonNode requiredObject(JsonNode parent, String name) {
    JsonNode value = parent.path(name);
    if (!value.isObject()) {
      throw TagoStopImportException.invalidResponse();
    }
    return value;
  }

  private static String requireText(JsonNode parent, String name) {
    JsonNode value = parent.path(name);
    if (!value.isTextual()) {
      throw TagoStopImportException.invalidResponse();
    }
    return value.asString();
  }

  private static int positiveOrZero(JsonNode parent, String name) {
    JsonNode value = parent.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw TagoStopImportException.invalidResponse();
    }
    return value.intValue();
  }

  private Envelope xmlEnvelope(byte[] payload) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Element root =
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(payload)).getDocumentElement();
    requireName(root, "response");
    Element header = one(root, "header");
    requireSuccess(text(one(header, "resultCode")));
    text(one(header, "resultMsg"));
    Element body = one(root, "body");
    Element itemsElement = one(body, "items");
    List<Map<String, String>> items = new ArrayList<>();
    for (Element item : children(itemsElement, "item")) {
      Map<String, String> fields = new LinkedHashMap<>();
      NodeList nodes = item.getChildNodes();
      for (int index = 0; index < nodes.getLength(); index++) {
        if (nodes.item(index) instanceof Element field) {
          requireNoNamespace(field);
          if (fields.putIfAbsent(field.getTagName(), text(field)) != null) {
            throw TagoStopImportException.invalidResponse();
          }
        } else if (nodes.item(index).getNodeType() != Node.TEXT_NODE
            || !nodes.item(index).getNodeValue().isBlank()) {
          throw TagoStopImportException.invalidResponse();
        }
      }
      items.add(fields);
    }
    return new Envelope(
        integer(text(one(body, "pageNo"))),
        integer(text(one(body, "numOfRows"))),
        integer(text(one(body, "totalCount"))),
        items);
  }

  private static Element one(Element parent, String name) {
    List<Element> matches = children(parent, name);
    if (matches.size() != 1) {
      throw TagoStopImportException.invalidResponse();
    }
    return matches.getFirst();
  }

  private static List<Element> children(Element parent, String name) {
    requireNoNamespace(parent);
    List<Element> result = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int index = 0; index < nodes.getLength(); index++) {
      if (nodes.item(index) instanceof Element element) {
        requireNoNamespace(element);
        if (name.equals(element.getTagName())) {
          result.add(element);
        }
      }
    }
    return result;
  }

  private static void requireName(Element element, String name) {
    requireNoNamespace(element);
    if (!name.equals(element.getTagName())) {
      throw TagoStopImportException.invalidResponse();
    }
  }

  private static void requireNoNamespace(Element element) {
    if (element.getNamespaceURI() != null || element.getPrefix() != null) {
      throw TagoStopImportException.invalidResponse();
    }
  }

  private static String text(Element element) {
    StringBuilder result = new StringBuilder();
    NodeList nodes = element.getChildNodes();
    for (int index = 0; index < nodes.getLength(); index++) {
      Node node = nodes.item(index);
      if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
        result.append(node.getNodeValue());
      } else {
        throw TagoStopImportException.invalidResponse();
      }
    }
    return result.toString();
  }

  private static void requireSuccess(String resultCode) {
    if (!SUCCESS_CODE.equals(resultCode)) {
      throw TagoStopImportException.invalidResponse();
    }
  }

  private static String required(Map<String, String> item, String name) {
    String value = item.get(name);
    if (value == null || value.isBlank()) {
      throw TagoStopImportException.invalidResponse();
    }
    return value.strip();
  }

  private static String optional(Map<String, String> item, String name) {
    String value = item.get(name);
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static double coordinate(String value) {
    try {
      double coordinate = Double.parseDouble(value);
      if (!Double.isFinite(coordinate)) {
        throw TagoStopImportException.invalidResponse();
      }
      return coordinate;
    } catch (NumberFormatException failure) {
      throw TagoStopImportException.invalidResponse();
    }
  }

  private static int integer(String value) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) {
        throw TagoStopImportException.invalidResponse();
      }
      return parsed;
    } catch (NumberFormatException failure) {
      throw TagoStopImportException.invalidResponse();
    }
  }

  private record Envelope(
      int pageNo, int numOfRows, int totalCount, List<Map<String, String>> items) {
    private Envelope {
      items = List.copyOf(items);
    }
  }
}
