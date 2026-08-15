package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.DetailIntroParser;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailIntro;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class TourApiDetailIntroParser implements DetailIntroParser {
  private static final Map<String, Set<String>> FIELDS =
      Map.of(
          "12",
              Set.of(
                  "infocenter",
                  "usetime",
                  "restdate",
                  "parking",
                  "chkpet",
                  "expguide",
                  "expagerange",
                  "heritage1",
                  "heritage2",
                  "heritage3"),
          "32",
              Set.of(
                  "infocenterlodging",
                  "checkintime",
                  "checkouttime",
                  "parkinglodging",
                  "reservationlodging",
                  "reservationurl",
                  "roomcount",
                  "pickup",
                  "foodplace",
                  "subfacility",
                  "barbecue",
                  "beverage",
                  "bicycle",
                  "campfire",
                  "fitness",
                  "karaoke",
                  "publicbath",
                  "publicpc",
                  "sauna",
                  "seminar",
                  "sports"),
          "39",
              Set.of(
                  "infocenterfood",
                  "opentimefood",
                  "restdatefood",
                  "parkingfood",
                  "reservationfood",
                  "firstmenu",
                  "treatmenu",
                  "smoking",
                  "packing",
                  "kidsfacility"));
  private final TourApiDetailJson json;

  public TourApiDetailIntroParser(ObjectMapper objectMapper) {
    json = new TourApiDetailJson(objectMapper);
  }

  @Override
  public PlaceDetailIntro parse(SnapshotPayloadFormat format, byte[] payload) {
    JsonNode item = json.item(format, payload);
    String contentId = TourApiDetailJson.requiredText(item, "contentid");
    String type = TourApiDetailJson.requiredText(item, "contenttypeid");
    Set<String> fields = FIELDS.get(type);
    if (fields == null) throw PlaceDetailImportException.invalidResponse();
    Map<String, String> raw = new LinkedHashMap<>();
    fields.stream().sorted().forEach(field -> put(raw, item, field));
    return switch (type) {
      case "12" ->
          detail(
              contentId,
              type,
              raw,
              "infocenter",
              "usetime",
              "restdate",
              "parking",
              "chkpet",
              null,
              List.of("expguide", "expagerange"),
              null);
      case "32" ->
          detail(
              contentId,
              type,
              raw,
              "infocenterlodging",
              null,
              null,
              "parkinglodging",
              null,
              null,
              List.of("subfacility", "foodplace"),
              "reservationlodging");
      case "39" ->
          detail(
              contentId,
              type,
              raw,
              "infocenterfood",
              "opentimefood",
              "restdatefood",
              "parkingfood",
              null,
              null,
              List.of("firstmenu", "treatmenu"),
              "reservationfood");
      default -> throw PlaceDetailImportException.invalidResponse();
    };
  }

  private static PlaceDetailIntro detail(
      String id,
      String type,
      Map<String, String> raw,
      String phone,
      String hours,
      String closed,
      String parking,
      String pet,
      String fee,
      List<String> facilities,
      String reservation) {
    String operation =
        hours == null && "32".equals(type)
            ? join(raw.get("checkintime"), raw.get("checkouttime"), " / ")
            : raw.get(hours);
    String facilityText =
        facilities.stream()
            .map(raw::get)
            .filter(value -> value != null)
            .reduce((a, b) -> a + " / " + b)
            .orElse(null);
    return new PlaceDetailIntro(
        id,
        type,
        raw.get(phone),
        operation,
        raw.get(closed),
        raw.get(parking),
        raw.get(pet),
        raw.get(fee),
        facilityText,
        raw.get(reservation),
        null,
        raw);
  }

  private static String join(String left, String right, String separator) {
    if (left == null) return right;
    if (right == null) return left;
    return left + separator + right;
  }

  private static void put(Map<String, String> target, JsonNode item, String field) {
    String value = TourApiDetailJson.optionalText(item, field);
    if (value != null) target.put(field, value);
  }
}
