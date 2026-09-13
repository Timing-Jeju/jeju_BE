package com.timingjeju.api.domain.schedule.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ScheduleLegRiskProjectionTest {
  @Test
  void 저장된_등급과_사유만_노출한다() {
    var risk =
        ScheduleLegRiskProjection.from(
            """
        {"generation":{"schemaVersion":1,"risks":[
          {"level":"low","reasonCodes":[]},
          {"level":"high","reasonCodes":["TRANSFER_SLACK_LOW"]}]}}
        """);
    assertThat(risk.level()).isEqualTo("high");
    assertThat(risk.reasonCodes()).containsExactly("TRANSFER_SLACK_LOW");
  }

  @Test
  void 근거_없는_일반_일정은_등급을_추정하지_않는다() {
    assertThat(ScheduleLegRiskProjection.from("{}").level()).isNull();
  }

  @Test
  void 사유에_자유문이_있으면_노출하지_않는다() {
    assertThatThrownBy(
            () ->
                ScheduleLegRiskProjection.from(
                    """
        {"generation":{"schemaVersion":1,"risks":[{"level":"low","reasonCodes":["사용자 원문"]}]}}
        """))
        .isInstanceOf(RuntimeException.class);
  }
}
