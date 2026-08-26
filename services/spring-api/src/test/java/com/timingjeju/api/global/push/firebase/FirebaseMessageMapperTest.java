package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import com.timingjeju.api.application.push.PushMessage;
import com.timingjeju.api.application.push.PushPlatformHints;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FirebaseMessageMapperTest {
  private static final String TRIP_ID = "aaaaaaaa-1111-4111-8111-111111111111";
  private static final String ITEM_ID = "22222222-2222-4222-8222-222222222222";
  private static final String VERSION_ID = "33333333-3333-4333-8333-333333333333";
  private static final Instant NOW = Instant.parse("2026-08-26T04:00:00Z");
  private static final JsonFactory JSON = GsonFactory.getDefaultInstance();

  private final FirebaseMessageMapper mapper =
      new FirebaseMessageMapper(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void notification_data와_Android_APNs_시간민감_설정을_함께_매핑한다() throws IOException {
    Message mapped = mapper.map(validMessage("다음 장소로 출발할 시간이에요", "09:20까지 성산일출봉(으)로 출발하세요."));

    String json = JSON.toString(mapped);

    assertThat(json)
        .contains("\"notification\"")
        .contains("다음 장소로 출발할 시간이에요")
        .contains("09:20까지 성산일출봉(으)로 출발하세요.")
        .contains("\"data\"")
        .contains("\"priority\":\"high\"")
        .contains("\"collapse_key\":\"trip:" + TRIP_ID + ":departure\"")
        .contains("\"ttl\":\"900s\"")
        .contains("\"apns-collapse-id\":\"trip:" + TRIP_ID + ":departure\"")
        .contains("\"apns-expiration\":\"1787717700\"")
        .contains("\"sound\":\"default\"");
    assertThat(json).doesNotContain("content_available");
  }

  @Test
  void 제목이나_본문이_UTF8_budget을_넘거나_control문자를_포함하면_둘다_결정적으로_fallback한다() throws IOException {
    Message overBudget = mapper.map(validMessage("가".repeat(27), "정상 본문"));
    Message control = mapper.map(validMessage("정상 제목", "본문\n삽입"));

    assertThat(JSON.toString(overBudget))
        .contains("출발 알림", "앱을 열어 다음 일정을 확인하세요.")
        .doesNotContain("가".repeat(27), "정상 본문");
    assertThat(JSON.toString(control))
        .contains("출발 알림", "앱을 열어 다음 일정을 확인하세요.")
        .doesNotContain("정상 제목", "본문\\n삽입");
  }

  @Test
  void data는_정확한_다섯_string_key와_canonical_UUID_deepLink만_허용한다() {
    Map<String, String> extra = new LinkedHashMap<>(validData());
    extra.put("registrationToken", "forbidden");
    assertThatThrownBy(() -> mapper.map(messageWithData(extra)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("data schema");

    Map<String, String> upperUuid = new LinkedHashMap<>(validData());
    upperUuid.put("tripId", TRIP_ID.toUpperCase());
    assertThatThrownBy(() -> mapper.map(messageWithData(upperUuid)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical UUID");

    Map<String, String> wrongLink = new LinkedHashMap<>(validData());
    wrongLink.put("deepLink", "timingjeju://trips/" + TRIP_ID + "/live?itemId=" + ITEM_ID + "&x=1");
    assertThatThrownBy(() -> mapper.map(messageWithData(wrongLink)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deepLink");
  }

  @Test
  void TTL은_1초부터_900초까지만_허용한다() {
    assertThat(mapper.map(withTtl(Duration.ofSeconds(1)))).isNotNull();
    assertThat(mapper.map(withTtl(Duration.ofSeconds(900)))).isNotNull();
    assertThatThrownBy(() -> mapper.map(withTtl(Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TTL");
    assertThatThrownBy(() -> mapper.map(withTtl(Duration.ofSeconds(901))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TTL");
  }

  private static PushMessage validMessage(String title, String body) {
    return new PushMessage(
        "test-registration-token",
        title,
        body,
        validData(),
        Duration.ofSeconds(900),
        "trip:" + TRIP_ID + ":departure",
        PushPlatformHints.visibleTimeSensitive());
  }

  private static PushMessage messageWithData(Map<String, String> data) {
    return new PushMessage(
        "test-registration-token",
        "정상 제목",
        "정상 본문",
        data,
        Duration.ofSeconds(60),
        "trip:" + TRIP_ID + ":departure",
        PushPlatformHints.visibleTimeSensitive());
  }

  private static PushMessage withTtl(Duration ttl) {
    return new PushMessage(
        "test-registration-token",
        "정상 제목",
        "정상 본문",
        validData(),
        ttl,
        "trip:" + TRIP_ID + ":departure",
        PushPlatformHints.visibleTimeSensitive());
  }

  private static Map<String, String> validData() {
    return Map.of(
        "contractVersion", "1.0.0",
        "tripId", TRIP_ID,
        "tripItemId", ITEM_ID,
        "scheduleVersionId", VERSION_ID,
        "deepLink", "timingjeju://trips/" + TRIP_ID + "/live?itemId=" + ITEM_ID);
  }
}
