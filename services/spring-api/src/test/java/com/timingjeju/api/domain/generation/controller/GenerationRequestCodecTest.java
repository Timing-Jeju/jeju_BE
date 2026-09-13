package com.timingjeju.api.domain.generation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationRequestCodecTest {
  private static final String DAY = "53000000-0000-0000-0000-000000000001";
  private final GenerationRequestCodec codec =
      new GenerationRequestCodec(JsonMapper.builder().build());

  @Test
  void 최초_생성의_null_활성버전을_허용한다() {
    var command = codec.decode(bytes(valid()));
    assertThat(command.targetDayId()).isEqualTo(UUID.fromString(DAY));
    assertThat(command.expectedActiveScheduleVersionId()).isNull();
    assertThat(command.candidateCount()).isEqualTo(3);
  }

  @Test
  void 필드순서와_공백은_멱등성_본문을_바꾸지_않는다() {
    var first = codec.decode(bytes(valid()));
    var second =
        codec.decode(
            bytes(
                "{\"candidateCount\":3, \"expectedActiveScheduleVersionId\":null,\"targetDayId\":\""
                    + DAY
                    + "\"}"));
    assertThat(codec.canonicalBody(first)).isEqualTo(codec.canonicalBody(second));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "[]", "", "{", "{\"targetDayId\":null}"})
  void 필수_필드와_객체가_없는_요청은_거부한다(String body) {
    assertThatThrownBy(() -> codec.decode(bytes(body))).hasMessage("INVALID_ASYNC_RUN_REQUEST");
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "4", "3.0", "\"3\"", "true", "null"})
  void 후보수는_정수_3만_허용한다(String count) {
    assertThatThrownBy(() -> codec.decode(bytes(valid().replace(":3}", ":" + count + "}"))))
        .hasMessage("INVALID_ASYNC_RUN_REQUEST");
  }

  @Test
  void 원문_좌표와_중복필드_후행JSON은_거부한다() {
    for (String suffix :
        new String[] {
          ",\"originalText\":\"text\"", ",\"coordinates\":{}", ",\"candidateCount\":3"
        }) {
      assertThatThrownBy(() -> codec.decode(bytes(valid().replace("}", suffix + "}"))))
          .hasMessage("INVALID_ASYNC_RUN_REQUEST");
    }
    assertThatThrownBy(() -> codec.decode(bytes(valid() + "{}")))
        .hasMessage("INVALID_ASYNC_RUN_REQUEST");
  }

  @Test
  void 축약UUID와_활성버전_필드생략은_거부한다() {
    assertThatThrownBy(() -> codec.decode(bytes(valid().replace(DAY, "1-1-1-1-1"))))
        .hasMessage("INVALID_ASYNC_RUN_REQUEST");
    assertThatThrownBy(
            () ->
                codec.decode(
                    bytes(valid().replace("\"expectedActiveScheduleVersionId\":null,", ""))))
        .hasMessage("INVALID_ASYNC_RUN_REQUEST");
  }

  private static String valid() {
    return "{\"targetDayId\":\""
        + DAY
        + "\",\"expectedActiveScheduleVersionId\":null,\"candidateCount\":3}";
  }

  @Test
  void null과_실제_활성버전은_서로_다른_멱등성_본문이다() {
    var empty = codec.decode(bytes(valid()));
    var active = codec.decode(bytes(valid().replace("null", "\"" + DAY + "\"")));
    assertThat(active.expectedActiveScheduleVersionId()).isEqualTo(UUID.fromString(DAY));
    assertThat(codec.canonicalBody(active)).isNotEqualTo(codec.canonicalBody(empty));
  }

  @Test
  void 과대본문과_null본문은_내용을_예외에_담지_않는다() {
    assertThatThrownBy(() -> codec.decode(new byte[GenerationRequestCodec.MAX_BODY_BYTES + 1]))
        .hasMessage("INVALID_ASYNC_RUN_REQUEST");
    assertThatThrownBy(() -> codec.decode(null)).hasMessage("INVALID_ASYNC_RUN_REQUEST");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
