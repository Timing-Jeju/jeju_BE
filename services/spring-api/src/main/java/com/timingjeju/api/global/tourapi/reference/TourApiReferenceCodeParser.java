package com.timingjeju.api.global.tourapi.reference;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.reference.ReferenceCode;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeOperation;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeParser;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
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
public final class TourApiReferenceCodeParser implements ReferenceCodeParser {

  private static final String SUCCESS_CODE = "0000";
  private static final String JEJU_REGION_CODE = "50";
  private final ObjectReader strictJsonReader;

  public TourApiReferenceCodeParser(ObjectMapper objectMapper) {
    this.strictJsonReader =
        Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.")
            .reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public List<ReferenceCode> parse(
      ReferenceCodeOperation operation, SnapshotPayloadFormat format, byte[] payload) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    Objects.requireNonNull(format, "format은 필수입니다.");
    Objects.requireNonNull(payload, "payload는 필수입니다.");
    try {
      List<Map<String, String>> items =
          switch (format) {
            case JSON -> jsonItems(payload);
            case XML -> xmlItems(payload);
            case TEXT, BINARY -> throw ReferenceCodeSyncException.invalidResponse();
          };
      List<ReferenceCode> codes =
          operation == ReferenceCodeOperation.LDONG
              ? parseLdong(items)
              : parseClassification(items);
      if (codes.isEmpty()) {
        throw ReferenceCodeSyncException.invalidResponse();
      }
      validateHierarchy(operation, codes);
      return List.copyOf(codes);
    } catch (ReferenceCodeSyncException failure) {
      throw failure;
    } catch (Exception ignored) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
  }

  private List<Map<String, String>> jsonItems(byte[] payload) {
    JsonNode root = strictJsonReader.readTree(payload);
    validateJsonStructure(root, "");
    JsonNode response = root.path("response");
    requireSuccess(response.path("header").path("resultCode").asString());
    JsonNode itemNode = response.path("body").path("items").path("item");
    List<Map<String, String>> result = new ArrayList<>();
    if (itemNode.isArray()) {
      itemNode.forEach(item -> result.add(jsonFields(item)));
    } else if (itemNode.isObject()) {
      result.add(jsonFields(itemNode));
    }
    return result;
  }

  private static Map<String, String> jsonFields(JsonNode item) {
    Map<String, String> fields = new LinkedHashMap<>();
    item.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().asString()));
    return fields;
  }

  private List<Map<String, String>> xmlItems(byte[] payload) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Element root =
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(payload)).getDocumentElement();
    requireNoNamespace(root);
    if (!"response".equals(root.getTagName())) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
    validateXmlStructure(root);
    Element header = directChild(root, "header");
    validateHeader(header);
    requireSuccess(scalarText(directChild(header, "resultCode")));
    Element items = directChild(directChild(root, "body"), "items");
    List<Element> itemNodes = directChildren(items, "item");
    List<Map<String, String>> result = new ArrayList<>();
    for (Element item : itemNodes) {
      Map<String, String> fields = new LinkedHashMap<>();
      NodeList children = item.getChildNodes();
      for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
        Node child = children.item(childIndex);
        if (child instanceof Element element) {
          requireNoNamespace(element);
          if (fields.putIfAbsent(element.getTagName(), scalarText(element)) != null) {
            throw ReferenceCodeSyncException.invalidResponse();
          }
        }
      }
      result.add(fields);
    }
    return result;
  }

  private static void requireSuccess(String resultCode) {
    if (!SUCCESS_CODE.equals(resultCode)) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
  }

  private static Element directChild(Element parent, String name) {
    List<Element> children = directChildren(parent, name);
    if (children.size() != 1) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
    return children.getFirst();
  }

  private static List<Element> directChildren(Element parent, String name) {
    requireNoNamespace(parent);
    List<Element> matches = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child instanceof Element element) {
        requireNoNamespace(element);
        if (name.equals(element.getTagName())) {
          matches.add(element);
        }
      }
    }
    return matches;
  }

  private static void requireNoNamespace(Element element) {
    if (element.getNamespaceURI() != null || element.getPrefix() != null) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
  }

  private static String scalarText(Element element) {
    StringBuilder value = new StringBuilder();
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
        value.append(child.getNodeValue());
      } else {
        throw ReferenceCodeSyncException.invalidResponse();
      }
    }
    return value.toString();
  }

  private static void validateHeader(Element header) {
    Set<String> fieldNames = new HashSet<>();
    NodeList children = header.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child instanceof Element field) {
        requireNoNamespace(field);
        if (!fieldNames.add(field.getTagName())) {
          throw ReferenceCodeSyncException.invalidResponse();
        }
        scalarText(field);
      } else if (child.getNodeType() != Node.TEXT_NODE || !child.getNodeValue().isBlank()) {
        throw ReferenceCodeSyncException.invalidResponse();
      }
    }
  }

  private static void validateXmlStructure(Element element) {
    requireNoNamespace(element);
    String parentName =
        element.getParentNode() instanceof Element parent ? parent.getTagName() : null;
    boolean validLocation =
        switch (element.getTagName()) {
          case "response" -> parentName == null;
          case "header", "body" -> "response".equals(parentName);
          case "resultCode" -> "header".equals(parentName);
          case "items" -> "body".equals(parentName);
          case "item" -> "items".equals(parentName);
          default -> true;
        };
    if (!validLocation) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element child) {
        validateXmlStructure(child);
      }
    }
  }

  private static void validateJsonStructure(JsonNode node, String path) {
    if (node.isArray()) {
      node.forEach(child -> validateJsonStructure(child, path));
      return;
    }
    if (!node.isObject()) {
      return;
    }
    node.properties()
        .forEach(
            entry -> {
              String childPath = path + '/' + entry.getKey();
              String expected =
                  switch (entry.getKey()) {
                    case "response" -> "/response";
                    case "header" -> "/response/header";
                    case "resultCode" -> "/response/header/resultCode";
                    case "body" -> "/response/body";
                    case "items" -> "/response/body/items";
                    case "item" -> "/response/body/items/item";
                    default -> null;
                  };
              if (expected != null && !expected.equals(childPath)) {
                throw ReferenceCodeSyncException.invalidResponse();
              }
              validateJsonStructure(entry.getValue(), childPath);
            });
  }

  private static List<ReferenceCode> parseLdong(List<Map<String, String>> items) {
    Map<String, ReferenceCode> unique = new LinkedHashMap<>();
    for (Map<String, String> item : items) {
      String regionCode = required(item, "lDongRegnCd");
      String regionName = required(item, "lDongRegnNm");
      if (!JEJU_REGION_CODE.equals(regionCode)) {
        continue;
      }
      putSame(unique, code("ldong-region", regionCode, null, regionName, regionName));
      String signguCode = optional(item, "lDongSignguCd");
      String signguName = optional(item, "lDongSignguNm");
      if ((signguCode == null) != (signguName == null)) {
        throw ReferenceCodeSyncException.invalidResponse();
      }
      if (signguCode != null) {
        putSame(
            unique,
            code(
                "ldong-signgu", signguCode, regionCode, signguName, regionName + "/" + signguName));
      }
    }
    return unique.values().stream()
        .sorted(
            Comparator.comparingInt(TourApiReferenceCodeParser::level)
                .thenComparing(ReferenceCode::externalCode))
        .toList();
  }

  private static List<ReferenceCode> parseClassification(List<Map<String, String>> items) {
    Map<String, ReferenceCode> unique = new LinkedHashMap<>();
    for (Map<String, String> item : items) {
      String first = optional(item, "lclsSystm1");
      String firstName = optional(item, "lclsSystm1Nm");
      String second = optional(item, "lclsSystm2");
      String secondName = optional(item, "lclsSystm2Nm");
      String third = optional(item, "lclsSystm3");
      String thirdName = optional(item, "lclsSystm3Nm");
      requirePair(first, firstName);
      requirePair(second, secondName);
      requirePair(third, thirdName);
      if ((second != null && first == null) || (third != null && second == null)) {
        throw ReferenceCodeSyncException.invalidHierarchy();
      }
      if (first != null) {
        putSame(unique, code("lcls-1", first, null, firstName, firstName));
      }
      if (second != null) {
        putSame(unique, code("lcls-2", second, first, secondName, firstName + "/" + secondName));
      }
      if (third != null) {
        putSame(
            unique,
            code(
                "lcls-3",
                third,
                second,
                thirdName,
                firstName + "/" + secondName + "/" + thirdName));
      }
    }
    return unique.values().stream()
        .sorted(
            Comparator.comparingInt(TourApiReferenceCodeParser::level)
                .thenComparing(ReferenceCode::externalCode))
        .toList();
  }

  private static void validateHierarchy(
      ReferenceCodeOperation operation, List<ReferenceCode> codes) {
    Map<String, ReferenceCode> byKey = new LinkedHashMap<>();
    codes.forEach(code -> byKey.put(code.codeType() + ':' + code.externalCode(), code));
    if (operation == ReferenceCodeOperation.LDONG
        && !byKey.containsKey("ldong-region:" + JEJU_REGION_CODE)) {
      throw ReferenceCodeSyncException.invalidHierarchy();
    }
    for (ReferenceCode code : codes) {
      if (code.parentExternalCode() == null) {
        continue;
      }
      String parentType =
          switch (code.codeType()) {
            case "ldong-signgu" -> "ldong-region";
            case "lcls-2" -> "lcls-1";
            case "lcls-3" -> "lcls-2";
            default -> throw ReferenceCodeSyncException.invalidHierarchy();
          };
      if (!byKey.containsKey(parentType + ':' + code.parentExternalCode())) {
        throw ReferenceCodeSyncException.invalidHierarchy();
      }
    }
  }

  private static ReferenceCode code(
      String type, String externalCode, String parent, String name, String path) {
    return new ReferenceCode(type, externalCode, parent, name, path, Map.of());
  }

  private static void putSame(Map<String, ReferenceCode> unique, ReferenceCode code) {
    String key = code.codeType() + ':' + code.externalCode();
    ReferenceCode existing = unique.putIfAbsent(key, code);
    if (existing != null && !existing.equals(code)) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
  }

  private static int level(ReferenceCode code) {
    return switch (code.codeType()) {
      case "ldong-region", "lcls-1" -> 1;
      case "ldong-signgu", "lcls-2" -> 2;
      case "lcls-3" -> 3;
      default -> 4;
    };
  }

  private static String required(Map<String, String> item, String field) {
    String value = optional(item, field);
    if (value == null) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
    return value;
  }

  private static String optional(Map<String, String> item, String field) {
    String value = item.get(field);
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }

  private static void requirePair(String code, String name) {
    if ((code == null) != (name == null)) {
      throw ReferenceCodeSyncException.invalidResponse();
    }
  }
}
