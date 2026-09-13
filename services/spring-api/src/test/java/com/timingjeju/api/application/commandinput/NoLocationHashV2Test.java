package com.timingjeju.api.application.commandinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class NoLocationHashV2Test {
  @org.junit.jupiter.api.Test
  void 검증_중_원본이_변경되어도_소유_snapshot만_hash한다() {
    var original =
        org.mockito.Mockito.spy(
            new ObjectMapper().createObjectNode().put("refreshExternalFacts", false));
    org.mockito.Mockito.doAnswer(
            call -> {
              original.putObject("currentLocation").put("latitude", 0);
              return call.callRealMethod();
            })
        .when(original)
        .get("refreshExternalFacts");
    var source = request(2, false);
    var mutable =
        new CommandInputRequest(
            source.parent(),
            source.runType(),
            2,
            source.contractVersion(),
            source.algorithmVersion(),
            original,
            source.ownerUserId(),
            source.tripPlanId(),
            source.baseScheduleVersionId());
    var result = new CommandInputCanonicalizer(new ObjectMapper()).canonicalize(mutable);
    assertThat(result.canonicalStructuredInput()).isEqualTo("{\"refreshExternalFacts\":false}");
  }

  private final ObjectMapper mapper = new ObjectMapper();
  private final CommandInputCanonicalizer canonicalizer = new CommandInputCanonicalizer(mapper);

  private CommandInputRequest request(int schemaVersion, boolean refresh) {
    return new CommandInputRequest(
        new CommandInputParent.Compute(UUID.fromString("44000000-0000-0000-0000-000000000001")),
        "feasibility",
        schemaVersion,
        "0.7.0",
        "fixture-only/v2",
        mapper.createObjectNode().put("refreshExternalFacts", refresh),
        UUID.fromString("44000000-0000-0000-0000-000000000002"),
        UUID.fromString("45000000-0000-0000-0000-000000000001"),
        null);
  }

  @Test
  void v2_hash는_위치_digest나_supplied_필드_없는_명시적_문서와_일치한다() throws Exception {
    String expectedDocument =
        "{\"algorithmVersion\":\"fixture-only/v2\",\"baseScheduleVersionId\":null,\"contractVersion\":\"0.7.0\",\"runType\":\"feasibility\",\"schemaVersion\":2,\"structuredInput\":{\"refreshExternalFacts\":false}}";
    String expected =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(expectedDocument.getBytes(StandardCharsets.UTF_8)));
    var snapshot = canonicalizer.canonicalize(request(2, false));
    assertThat(snapshot.schemaVersion()).isEqualTo(2);
    assertThat(snapshot.commandInputHash().equals(expected)).as("위치 필드 없는 v2 문서 hash 일치").isTrue();
  }

  @Test
  void v2_동일_입력은_결정적이고_facts_갱신_선택은_hash에_반영한다() {
    var first = canonicalizer.canonicalize(request(2, false));
    var same = canonicalizer.canonicalize(request(2, false));
    var refresh = canonicalizer.canonicalize(request(2, true));
    assertThat(first.commandInputHash().equals(same.commandInputHash())).isTrue();
    assertThat(first.commandInputHash().equals(refresh.commandInputHash())).isFalse();
  }

  @Test
  void v1_요청을_v2로_암묵적으로_재해석하지_않는다() {
    assertThatThrownBy(() -> canonicalizer.canonicalize(request(1, false)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 요청과_snapshot의_공개_record에는_위치_필드가_없다() {
    assertThat(
            Arrays.stream(CommandInputRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
        .doesNotContain("location");
    assertThat(
            Arrays.stream(CommandInputSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
        .doesNotContain("locationSnapshot");
  }

  @Test
  void 저장된_v1_snapshot도_v2로_암묵_복원하지_않는다() {
    var valid = canonicalizer.canonicalize(request(2, false));
    assertThatThrownBy(
            () ->
                new CommandInputSnapshot(
                    valid.parent(),
                    valid.runType(),
                    1,
                    valid.contractVersion(),
                    valid.algorithmVersion(),
                    valid.canonicalStructuredInput(),
                    valid.commandInputHash(),
                    valid.ownerUserId(),
                    valid.tripPlanId(),
                    valid.baseScheduleVersionId()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
