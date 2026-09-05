package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.trip.ReplaceTripPreferencesCommand;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPreferencePolicy;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TripPreferencesHttpCodecTest {
  private final TripPreferencesRequestCodec codec =
      new TripPreferencesRequestCodec(new ObjectMapper());

  @Test
  void exact_1MiB_body는_허용하고_max_plus_one은_stream에서_거부한다() {
    byte[] valid = validJson().getBytes(StandardCharsets.UTF_8);
    byte[] exact = padded(valid, TripPreferencesRequestBoundary.MAX_BODY_BYTES);
    byte[] tooLarge = padded(valid, TripPreferencesRequestBoundary.MAX_BODY_BYTES + 1);

    assertThat(codec.decode(read(exact)).transportModes()).hasSize(1);
    assertInvalid(() -> read(tooLarge));
  }

  @Test
  void duplicate_unknown_missing_null_wrong_type은_INVALID_REQUEST다() {
    String valid = validJson();
    for (String invalid :
        new String[] {
          valid.replaceFirst("\\{", "{\"preferredCategories\":[\"cafe\"],"),
          valid.replace("\"transportModes\":", "\"unknown\":true,\"transportModes\":"),
          valid.replace("\"preferredCategories\":[],", ""),
          valid.replace("\"arrivalRegionCode\":\"jeju-si\"", "\"arrivalRegionCode\":null"),
          valid.replace("\"departureRegionCode\":\"jeju-si\"", "\"departureRegionCode\":44")
        }) {
      assertInvalid(() -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void top_level_JSON_null은_INVALID_REQUEST다() {
    assertInvalid(() -> codec.decode("null".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void nested_transport_fields와_null_category_trim_blank_region은_INVALID_REQUEST다() {
    for (InvalidJson invalid : structuralInvalidBodies(validJson())) {
      assertInvalid(
          () -> codec.decode(invalid.body().getBytes(StandardCharsets.UTF_8)), invalid.label());
    }
  }

  @Test
  void request_DTO는_여섯_ASCII공백을독립검증하고_그밖의_C0제어문자를보존한다() {
    for (String encodedWhitespace : List.of(" ", "\\t", "\\n", "\\r", "\\f", unicode("000b"))) {
      String invalid =
          validJson()
              .replace(
                  "\"arrivalRegionCode\":\"jeju-si\"",
                  "\"arrivalRegionCode\":\"" + encodedWhitespace + "\"");
      assertInvalid(() -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)));
    }

    String preserved = "\u0001\b";
    String encodedPreserved = unicode("0001") + "\\b";
    var command =
        codec.decode(
            validJson()
                .replace(
                    "\"arrivalRegionCode\":\"jeju-si\"",
                    "\"arrivalRegionCode\":\"" + encodedPreserved + "\"")
                .replace(
                    "\"preferredRegionCodes\":[]",
                    "\"preferredRegionCodes\":[\"" + encodedPreserved + "\"]")
                .getBytes(StandardCharsets.UTF_8));

    assertThat(command.arrivalRegionCode()).isEqualTo(preserved);
    assertThat(command.preferredRegionCodes()).containsExactly(preserved);
  }

  @Test
  void 세_region필드는_ASCII_trim_NFC후_50을허용하고_51을거부한다() {
    String encodedTrim = " \\t\\n\\r\\f" + unicode("000b");
    String composed50 = "é".repeat(50);
    String decomposed50 = "e\u0301".repeat(50);

    for (String field :
        List.of("arrivalRegionCode", "departureRegionCode", "preferredRegionCodes")) {
      ReplaceTripPreferencesCommand composed =
          TripPreferencePolicy.canonicalizeAndValidate(
              codec.decode(
                  regionJson(field, encodedTrim + composed50 + encodedTrim)
                      .getBytes(StandardCharsets.UTF_8)));
      ReplaceTripPreferencesCommand decomposed =
          TripPreferencePolicy.canonicalizeAndValidate(
              codec.decode(
                  regionJson(field, encodedTrim + decomposed50 + encodedTrim)
                      .getBytes(StandardCharsets.UTF_8)));

      assertThat(regionValue(composed, field)).isEqualTo(composed50);
      assertThat(regionValue(decomposed, field)).isEqualTo(composed50);
      for (String tooLong : List.of("é".repeat(51), "e\u0301".repeat(51))) {
        assertInvalid(
            () -> codec.decode(regionJson(field, tooLong).getBytes(StandardCharsets.UTF_8)), field);
      }
    }
  }

  @Test
  void JSON_NUL은_codec에서_INVALID_REQUEST다() {
    String invalid =
        validJson()
            .replace(
                "\"arrivalRegionCode\":\"jeju-si\"",
                "\"arrivalRegionCode\":\"" + unicode("0000") + "jeju-si\"");

    assertInvalid(() -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void category와_region배열의_numeric_boolean_element는_INVALID_REQUEST다() {
    String valid = validJson();
    for (String invalid :
        List.of(
            valid.replace("\"preferredCategories\":[]", "\"preferredCategories\":[7]"),
            valid.replace("\"preferredCategories\":[]", "\"preferredCategories\":[true]"),
            valid.replace("\"preferredRegionCodes\":[]", "\"preferredRegionCodes\":[7]"),
            valid.replace("\"preferredRegionCodes\":[]", "\"preferredRegionCodes\":[false]"))) {
      assertInvalid(() -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void malformed_length와_transfer_encoding은_codec과_auth보다_먼저_거부한다() {
    for (String invalidLength : new String[] {"-1", "+0", "00", "zero", "0,0"}) {
      MockHttpServletRequest request = request(validJson().getBytes(StandardCharsets.UTF_8));
      request.removeHeader(HttpHeaders.CONTENT_LENGTH);
      request.addHeader(HttpHeaders.CONTENT_LENGTH, invalidLength);
      assertInvalid(() -> TripPreferencesRequestBoundary.readRequiredBody(request));
    }
    MockHttpServletRequest chunked = request(validJson().getBytes(StandardCharsets.UTF_8));
    chunked.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
    assertInvalid(() -> TripPreferencesRequestBoundary.readRequiredBody(chunked));
  }

  @Test
  void content_length_cardinality_overflow와_raw_servlet불일치는_거부한다() {
    byte[] body = validJson().getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest duplicate = request(body);
    duplicate.addHeader(HttpHeaders.CONTENT_LENGTH, body.length);
    duplicate.addHeader(HttpHeaders.CONTENT_LENGTH, body.length);
    assertInvalid(() -> TripPreferencesRequestBoundary.readRequiredBody(duplicate));

    MockHttpServletRequest overflow = request(body);
    overflow.addHeader(HttpHeaders.CONTENT_LENGTH, "9223372036854775808");
    assertInvalid(() -> TripPreferencesRequestBoundary.readRequiredBody(overflow));

    assertInvalid(
        () ->
            TripPreferencesRequestBoundary.readRequiredBody(
                new ReportedLengthRequest(request(body), body.length - 1L)));
    assertInvalid(
        () ->
            TripPreferencesRequestBoundary.readRequiredBody(
                new ReportedLengthRequest(request(body), body.length + 1L)));
  }

  @Test
  void empty_truncated_trailing_token과_IO_failure를_거부한다() {
    assertInvalid(() -> codec.decode(read(new byte[0])));
    assertInvalid(
        () ->
            codec.decode(
                read(
                    validJson()
                        .substring(0, validJson().length() - 4)
                        .getBytes(StandardCharsets.UTF_8))));
    assertInvalid(
        () -> codec.decode(read((validJson() + " true").getBytes(StandardCharsets.UTF_8))));
    assertInvalid(
        () ->
            TripPreferencesRequestBoundary.readRequiredBody(
                new FailingInputRequest(request(validJson().getBytes(StandardCharsets.UTF_8)))));
  }

  @Test
  void query_media_type와_IfMatch_cardinality_comma_weak_nonnumeric_zero_UUID_revision을_거부한다() {
    MockHttpServletRequest query = request(validJson().getBytes(StandardCharsets.UTF_8));
    query.addParameter("unexpected", "1");
    assertInvalid(() -> TripPreferencesRequestBoundary.requireNoQuery(query));

    for (String mediaType : new String[] {null, "text/plain", "application/problem+json"}) {
      MockHttpServletRequest invalid = request(validJson().getBytes(StandardCharsets.UTF_8));
      invalid.setContentType(mediaType);
      assertInvalid(() -> TripPreferencesRequestBoundary.requireJsonMediaType(invalid));
    }

    UUID trip = UUID.fromString("46000000-0000-0000-0000-000000000002");
    for (String value :
        new String[] {
          "trip-1", "W/\"trip-1\"", "\"opaque\"", "\"trip-0\"", "\"trip-" + trip + "-1\""
        }) {
      MockHttpServletRequest invalid = request(validJson().getBytes(StandardCharsets.UTF_8));
      invalid.addHeader(HttpHeaders.IF_MATCH, value);
      assertInvalid(
          () -> {
            TripPreferencesRequestBoundary.requiredRevision(invalid, trip);
          });
    }
    MockHttpServletRequest comma = request(validJson().getBytes(StandardCharsets.UTF_8));
    comma.addHeader(HttpHeaders.IF_MATCH, "\"trip-1\",\"trip-2\"");
    assertInvalid(
        () -> TripPreferencesRequestBoundary.requiredSingleHeader(comma, HttpHeaders.IF_MATCH));
    MockHttpServletRequest duplicate = request(validJson().getBytes(StandardCharsets.UTF_8));
    duplicate.addHeader(HttpHeaders.IF_MATCH, "\"trip-1\"");
    duplicate.addHeader(HttpHeaders.IF_MATCH, "\"trip-1\"");
    assertInvalid(
        () -> TripPreferencesRequestBoundary.requiredSingleHeader(duplicate, HttpHeaders.IF_MATCH));
  }

  private static byte[] read(byte[] body) {
    return TripPreferencesRequestBoundary.readRequiredBody(request(body));
  }

  private static MockHttpServletRequest request(byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType("application/json");
    request.setContent(body);
    return request;
  }

  private static byte[] padded(byte[] prefix, int size) {
    byte[] result = new byte[size];
    System.arraycopy(prefix, 0, result, 0, prefix.length);
    java.util.Arrays.fill(result, prefix.length, size, (byte) ' ');
    return result;
  }

  private static String unicode(String hex) {
    return "\\" + "u" + hex;
  }

  private static String regionJson(String field, String encodedValue) {
    return switch (field) {
      case "arrivalRegionCode" ->
          validJson().replace("\"arrivalRegionCode\":\"jeju-si\"", jsonField(field, encodedValue));
      case "departureRegionCode" ->
          validJson()
              .replace("\"departureRegionCode\":\"jeju-si\"", jsonField(field, encodedValue));
      case "preferredRegionCodes" ->
          validJson()
              .replace(
                  "\"preferredRegionCodes\":[]",
                  "\"preferredRegionCodes\":[\"" + encodedValue + "\"]");
      default -> throw new IllegalArgumentException(field);
    };
  }

  private static String jsonField(String field, String encodedValue) {
    return "\"" + field + "\":\"" + encodedValue + "\"";
  }

  private static String regionValue(ReplaceTripPreferencesCommand command, String field) {
    return switch (field) {
      case "arrivalRegionCode" -> command.arrivalRegionCode();
      case "departureRegionCode" -> command.departureRegionCode();
      case "preferredRegionCodes" -> command.preferredRegionCodes().getFirst();
      default -> throw new IllegalArgumentException(field);
    };
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertInvalid(action, "invalid request");
  }

  private static void assertInvalid(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String label) {
    assertThatThrownBy(action)
        .as(label)
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("INVALID_REQUEST");
  }

  private static List<InvalidJson> structuralInvalidBodies(String valid) {
    return List.of(
        new InvalidJson("mode missing", valid.replace("\"mode\":\"public_transit\",", "")),
        new InvalidJson("mode null", valid.replace("\"mode\":\"public_transit\"", "\"mode\":null")),
        new InvalidJson(
            "mode wrong type", valid.replace("\"mode\":\"public_transit\"", "\"mode\":7")),
        new InvalidJson("priority missing", valid.replace("\"priority\":1,", "")),
        new InvalidJson("priority null", valid.replace("\"priority\":1", "\"priority\":null")),
        new InvalidJson(
            "priority wrong type", valid.replace("\"priority\":1", "\"priority\":\"1\"")),
        new InvalidJson("primary missing", valid.replace(",\"primary\":true", "")),
        new InvalidJson("primary null", valid.replace("\"primary\":true", "\"primary\":null")),
        new InvalidJson("primary wrong type", valid.replace("\"primary\":true", "\"primary\":1")),
        new InvalidJson(
            "unknown nested property",
            valid.replace(
                "\"mode\":\"public_transit\"", "\"unknown\":true,\"mode\":\"public_transit\"")),
        new InvalidJson(
            "null preferred category",
            valid.replace("\"preferredCategories\":[]", "\"preferredCategories\":[null]")),
        new InvalidJson(
            "blank preferred region after trim",
            valid.replace(
                "\"preferredRegionCodes\":[]", "\"preferredRegionCodes\":[\"  \\t  \"]")));
  }

  private static String validJson() {
    return """
    {"preferredCategories":[],"arrivalRegionCode":"jeju-si","departureRegionCode":"jeju-si",
    "preferredRegionCodes":[],"startPlaceId":null,"endPlaceId":null,
    "transportModes":[{"mode":"public_transit","priority":1,"primary":true}]}
    """;
  }

  private record InvalidJson(String label, String body) {}

  private static final class ReportedLengthRequest extends HttpServletRequestWrapper {
    private final long reported;

    private ReportedLengthRequest(HttpServletRequest request, long reported) {
      super(request);
      this.reported = reported;
    }

    @Override
    public long getContentLengthLong() {
      return reported;
    }
  }

  private static final class FailingInputRequest extends HttpServletRequestWrapper {
    private FailingInputRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ServletInputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("test-only");
        }

        @Override
        public boolean isFinished() {
          return false;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {}
      };
    }
  }
}
