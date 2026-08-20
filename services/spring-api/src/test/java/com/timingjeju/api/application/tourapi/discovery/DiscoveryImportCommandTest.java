package com.timingjeju.api.application.tourapi.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.Normalizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DiscoveryImportCommandTest {

  @Test
  void keyword는_NFC로_정규화하고_제주_법정동_scope와_page_quota를_고정한다() {
    String decomposed = "성산  일출봉";

    DiscoveryImportCommand command =
        DiscoveryImportCommand.keyword(decomposed, 3, "issue-75-keyword-v1");

    assertThat(command.operation()).isEqualTo(DiscoveryOperation.KEYWORD);
    assertThat(command.keyword()).isEqualTo(Normalizer.normalize("성산 일출봉", Normalizer.Form.NFC));
    assertThat(command.legalRegionCode()).isEqualTo("50");
    assertThat(command.pageBudget()).isEqualTo(3);
  }

  @Test
  void location은_제주_좌표와_공식_반경_범위를_벗어나면_거부한다() {
    assertThatThrownBy(() -> DiscoveryImportCommand.location(127.1, 33.5, 1000, 2, "outside-jeju"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> DiscoveryImportCommand.location(126.5, 33.5, 20001, 2, "radius-too-wide"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void manual_command의_page_quota는_1에서_100까지만_허용한다() {
    assertThatThrownBy(() -> DiscoveryImportCommand.stay(0, "zero"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DiscoveryImportCommand.stay(101, "too-many"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
