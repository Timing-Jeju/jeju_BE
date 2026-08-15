package com.timingjeju.api.global.tourapi.detailitem;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detailitem.DetailInfoParser;
import com.timingjeju.api.application.tourapi.detailitem.DetailItem;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemAttributes;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportException;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class TourApiDetailInfoParser implements DetailInfoParser {
  private static final Set<String> GENERIC_TYPES = Set.of("12", "14", "15", "28", "38");
  private static final ItemContract GENERIC =
      new ItemContract(
          "info",
          List.of("serialnum"),
          "infoname",
          List.of("fldgubun", "infoname", "infotext"),
          Set.of("infotext"),
          Set.of());
  private static final ItemContract COURSE =
      new ItemContract(
          "course",
          List.of("subcontentid"),
          "subname",
          List.of("subname", "subnum", "subdetailalt", "subdetailimg", "subdetailoverview"),
          Set.of("subdetailoverview"),
          Set.of("subdetailimg"));
  private static final ItemContract ROOM =
      new ItemContract(
          "room",
          List.of("roomcode"),
          "roomtitle",
          List.of(
              "roomtitle",
              "roomsize1",
              "roomcount",
              "roombasecount",
              "roommaxcount",
              "roomoffseasonminfee1",
              "roomoffseasonminfee2",
              "roompeakseasonminfee1",
              "roompeakseasonminfee2",
              "roomintro",
              "roombathfacility",
              "roombath",
              "roomhometheater",
              "roomaircondition",
              "roomtv",
              "roompc",
              "roomcable",
              "roominternet",
              "roomrefrigerator",
              "roomtoiletries",
              "roomsofa",
              "roomcook",
              "roomtable",
              "roomhairdryer",
              "roomsize2",
              "roomimg1",
              "roomimg1alt",
              "roomimg2",
              "roomimg2alt",
              "roomimg3",
              "roomimg3alt",
              "roomimg4",
              "roomimg4alt",
              "roomimg5",
              "roomimg5alt"),
          Set.of("roomintro"),
          Set.of("roomimg1", "roomimg2", "roomimg3", "roomimg4", "roomimg5"));
  private static final ItemContract MENU =
      new ItemContract(
          "menu",
          List.of("serialnum", "foodmenu"),
          "foodmenu",
          List.of("foodmenu", "foodcost", "foodimg", "treatmenu"),
          Set.of(),
          Set.of("foodimg"));

  private final ObjectReader reader;
  private final DetailItemContentSanitizer sanitizer;

  public TourApiDetailInfoParser(ObjectMapper objectMapper, DetailItemContentSanitizer sanitizer) {
    this.reader = objectMapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    this.sanitizer = sanitizer;
  }

  @Override
  public DetailItemPage parse(
      SnapshotPayloadFormat format, byte[] payload, String contentId, String contentTypeId) {
    if (format != SnapshotPayloadFormat.JSON
        || payload == null
        || contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()) {
      throw DetailItemImportException.invalidResponse();
    }
    ItemContract contract = contract(contentTypeId);
    try {
      JsonNode response = reader.readTree(payload).path("response");
      if (!response.path("header").path("resultCode").isTextual()
          || !"0000".equals(response.path("header").path("resultCode").asString())) {
        throw DetailItemImportException.invalidResponse();
      }
      JsonNode body = response.path("body");
      int pageNo = positiveInt(body.path("pageNo"));
      int numOfRows = positiveInt(body.path("numOfRows"));
      int totalCount = nonNegativeInt(body.path("totalCount"));
      JsonNode itemsNode = body.path("items");
      if (!body.isObject() || itemsNode.isMissingNode()) {
        throw DetailItemImportException.invalidResponse();
      }
      if ((itemsNode.isNull() || (itemsNode.isTextual() && itemsNode.asString().isBlank()))
          && totalCount == 0) {
        return new DetailItemPage(
            contentId, contentTypeId, pageNo, numOfRows, totalCount, 0, List.of());
      }
      JsonNode node = itemsNode.path("item");
      if (!node.isArray()) throw DetailItemImportException.invalidResponse();
      List<DetailItem> items = new ArrayList<>();
      for (int index = 0; index < node.size(); index++) {
        JsonNode item = node.get(index);
        if (!item.isObject()
            || !contentId.equals(requiredText(item, "contentid"))
            || !contentTypeId.equals(requiredText(item, "contenttypeid"))) {
          throw DetailItemImportException.invalidResponse();
        }
        String key = firstRequired(item, contract.keyFields());
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (String field : contract.fields()) {
          String value = optionalText(item, field);
          if (value == null) continue;
          if (contract.htmlFields().contains(field)) value = sanitizer.plainText(value);
          if (contract.urlFields().contains(field)) value = sanitizer.safeUrl(value);
          if (value != null && !value.isBlank()) fields.put(field, value);
        }
        items.add(
            new DetailItem(
                contract.itemType(),
                key,
                optionalText(item, contract.titleField()),
                index + 1,
                new DetailItemAttributes(
                    "tour-api.detailInfo2." + contract.itemType(), 1, fields)));
      }
      return new DetailItemPage(
          contentId, contentTypeId, pageNo, numOfRows, totalCount, node.size(), items);
    } catch (DetailItemImportException failure) {
      throw failure;
    } catch (Exception ignored) {
      throw DetailItemImportException.invalidResponse();
    }
  }

  private static ItemContract contract(String contentTypeId) {
    if (GENERIC_TYPES.contains(contentTypeId)) return GENERIC;
    return switch (contentTypeId) {
      case "25" -> COURSE;
      case "32" -> ROOM;
      case "39" -> MENU;
      default -> throw DetailItemImportException.invalidResponse();
    };
  }

  private static String firstRequired(JsonNode item, List<String> fields) {
    for (String field : fields) {
      String value = optionalText(item, field);
      if (value != null) return value;
    }
    throw DetailItemImportException.invalidResponse();
  }

  private static String requiredText(JsonNode item, String field) {
    String value = optionalText(item, field);
    if (value == null) throw DetailItemImportException.invalidResponse();
    return value;
  }

  private static String optionalText(JsonNode item, String field) {
    if (!item.has(field) || item.path(field).isNull()) return null;
    JsonNode value = item.path(field);
    if (!value.isTextual()) throw DetailItemImportException.invalidResponse();
    String text = value.asString().strip();
    return text.isEmpty() ? null : text;
  }

  private static int positiveInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 1) throw DetailItemImportException.invalidResponse();
    return value;
  }

  private static int nonNegativeInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 0) throw DetailItemImportException.invalidResponse();
    return value;
  }

  private static int exactInt(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw DetailItemImportException.invalidResponse();
    }
    return node.asInt();
  }

  private record ItemContract(
      String itemType,
      List<String> keyFields,
      String titleField,
      List<String> fields,
      Set<String> htmlFields,
      Set<String> urlFields) {}
}
