package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.route.TagoRoute;
import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import com.timingjeju.api.application.tago.route.TagoRoutePage;
import com.timingjeju.api.application.tago.route.TagoRoutePayloadParser;
import com.timingjeju.api.application.tago.route.TagoRouteStop;
import com.timingjeju.api.application.tago.route.TagoRouteStopPage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class TagoRouteParser implements TagoRoutePayloadParser {
  private final ObjectReader reader;

  public TagoRouteParser(ObjectMapper mapper) {
    reader = mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public TagoRoutePage parseRouteList(
      SnapshotPayloadFormat format, byte[] payload, String city, String routeNo, int expectedPage) {
    Envelope envelope = envelope(format, payload);
    checkPage(envelope, expectedPage);
    List<TagoRoute> routes = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Map<String, String> item : envelope.items()) {
      String id = required(item, "routeid", 30);
      String actualNo = required(item, "routeno", 30);
      if (!actualNo.equals(routeNo) || !ids.add(id))
        throw TagoRouteImportException.invalidResponse();
      routes.add(new TagoRoute(city, id, actualNo, required(item, "routetp", 10), "", "", id));
    }
    return new TagoRoutePage(
        envelope.pageNo(), envelope.numOfRows(), envelope.totalCount(), routes);
  }

  @Override
  public TagoRoute parseRouteDetail(
      SnapshotPayloadFormat format, byte[] payload, String city, String expectedRouteId) {
    Envelope envelope = envelope(format, payload);
    if (envelope.items().size() != 1) throw TagoRouteImportException.invalidResponse();
    Map<String, String> item = envelope.items().getFirst();
    String id = required(item, "routeid", 30);
    if (!id.equals(expectedRouteId)) throw TagoRouteImportException.invalidResponse();
    return new TagoRoute(
        city,
        id,
        required(item, "routeno", 30),
        required(item, "routetp", 10),
        required(item, "startnodenm", 30),
        required(item, "endnodenm", 30),
        id);
  }

  @Override
  public TagoRouteStopPage parseRouteStops(
      SnapshotPayloadFormat format, byte[] payload, String city, String routeId, int expectedPage) {
    Envelope envelope = envelope(format, payload);
    checkPage(envelope, expectedPage);
    List<TagoRouteStop> stops = new ArrayList<>();
    Set<Integer> sequences = new HashSet<>();
    Set<String> nodes = new HashSet<>();
    int previous = (expectedPage - 1) * envelope.numOfRows();
    for (Map<String, String> item : envelope.items()) {
      if (item.containsKey("citycode") && !city.equals(required(item, "citycode")))
        throw TagoRouteImportException.invalidResponse();
      if (item.containsKey("routeid") && !routeId.equals(required(item, "routeid")))
        throw TagoRouteImportException.invalidResponse();
      String node = required(item, "nodeid", 30);
      int sequence = positive(required(item, "nodeord"));
      if (sequence != previous + 1 || !sequences.add(sequence) || !nodes.add(node))
        throw TagoRouteImportException.invalidResponse();
      previous = sequence;
      stops.add(new TagoRouteStop(city, routeId, node, sequence));
    }
    return new TagoRouteStopPage(
        envelope.pageNo(), envelope.numOfRows(), envelope.totalCount(), stops);
  }

  private Envelope envelope(SnapshotPayloadFormat format, byte[] payload) {
    try {
      return switch (format) {
        case JSON -> json(payload);
        case XML -> xml(payload);
        default -> throw TagoRouteImportException.invalidResponse();
      };
    } catch (TagoRouteImportException failure) {
      throw failure;
    } catch (Exception failure) {
      throw TagoRouteImportException.invalidResponse();
    }
  }

  private Envelope json(byte[] payload) throws Exception {
    JsonNode root = reader.readTree(payload);
    JsonNode response = object(root, "response");
    JsonNode header = object(response, "header");
    if (!"00".equals(text(header, "resultCode"))) throw TagoRouteImportException.invalidResponse();
    text(header, "resultMsg");
    JsonNode body = object(response, "body");
    JsonNode items = object(body, "items").path("item");
    List<Map<String, String>> values = new ArrayList<>();
    if (items.isArray()) items.forEach(item -> values.add(jsonItem(item)));
    else if (items.isObject()) values.add(jsonItem(items));
    else if (!items.isNull() && !items.isMissingNode())
      throw TagoRouteImportException.invalidResponse();
    return new Envelope(
        integer(body, "pageNo", 1),
        integer(body, "numOfRows", values.size()),
        integer(body, "totalCount", values.size()),
        values);
  }

  private static Map<String, String> jsonItem(JsonNode item) {
    if (!item.isObject()) throw TagoRouteImportException.invalidResponse();
    Map<String, String> result = new LinkedHashMap<>();
    item.properties()
        .forEach(
            field -> {
              if (!field.getValue().isTextual()) throw TagoRouteImportException.invalidResponse();
              result.put(field.getKey(), field.getValue().asString());
            });
    return result;
  }

  private Envelope xml(byte[] payload) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Element root =
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(payload)).getDocumentElement();
    if (!"response".equals(root.getTagName())) throw TagoRouteImportException.invalidResponse();
    Element header = one(root, "header");
    if (!"00".equals(value(one(header, "resultCode"))))
      throw TagoRouteImportException.invalidResponse();
    value(one(header, "resultMsg"));
    Element body = one(root, "body");
    List<Map<String, String>> values = new ArrayList<>();
    for (Element item : children(one(body, "items"), "item")) {
      Map<String, String> fields = new LinkedHashMap<>();
      for (int index = 0; index < item.getChildNodes().getLength(); index++)
        if (item.getChildNodes().item(index) instanceof Element field) {
          if (fields.putIfAbsent(field.getTagName(), value(field)) != null)
            throw TagoRouteImportException.invalidResponse();
        }
      values.add(fields);
    }
    return new Envelope(
        optionalInteger(body, "pageNo", 1),
        optionalInteger(body, "numOfRows", values.size()),
        optionalInteger(body, "totalCount", values.size()),
        values);
  }

  private static JsonNode object(JsonNode parent, String name) {
    JsonNode result = parent.path(name);
    if (!result.isObject()) throw TagoRouteImportException.invalidResponse();
    return result;
  }

  private static String text(JsonNode parent, String name) {
    JsonNode result = parent.path(name);
    if (!result.isTextual()) throw TagoRouteImportException.invalidResponse();
    return result.asString();
  }

  private static int integer(JsonNode parent, String name, int fallback) {
    JsonNode result = parent.path(name);
    if (result.isMissingNode()) return fallback;
    if (!result.isIntegralNumber() || !result.canConvertToInt() || result.intValue() < 0)
      throw TagoRouteImportException.invalidResponse();
    return result.intValue();
  }

  private static Element one(Element parent, String name) {
    List<Element> result = children(parent, name);
    if (result.size() != 1) throw TagoRouteImportException.invalidResponse();
    return result.getFirst();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++)
      if (nodes.item(i) instanceof Element element && name.equals(element.getTagName()))
        result.add(element);
    return result;
  }

  private static String value(Element element) {
    return element.getTextContent().trim();
  }

  private static int optionalInteger(Element parent, String name, int fallback) {
    List<Element> found = children(parent, name);
    return found.isEmpty() ? fallback : positiveOrZero(value(found.getFirst()));
  }

  private static String required(Map<String, String> item, String name) {
    return required(item, name, 512);
  }

  private static String required(Map<String, String> item, String name, int maxLength) {
    String value = item.get(name);
    if (value == null || value.isBlank() || value.length() > maxLength)
      throw TagoRouteImportException.invalidResponse();
    return value;
  }

  private static int positive(String value) {
    int result = positiveOrZero(value);
    if (result < 1) throw TagoRouteImportException.invalidResponse();
    return result;
  }

  private static int positiveOrZero(String value) {
    try {
      int result = Integer.parseInt(value);
      if (result < 0) throw TagoRouteImportException.invalidResponse();
      return result;
    } catch (NumberFormatException failure) {
      throw TagoRouteImportException.invalidResponse();
    }
  }

  private static void checkPage(Envelope envelope, int expected) {
    if (envelope.pageNo() != expected
        || envelope.numOfRows() < 1
        || envelope.totalCount() < 0
        || envelope.items().size() > envelope.numOfRows())
      throw TagoRouteImportException.invalidResponse();
  }

  private record Envelope(
      int pageNo, int numOfRows, int totalCount, List<Map<String, String>> items) {}
}
