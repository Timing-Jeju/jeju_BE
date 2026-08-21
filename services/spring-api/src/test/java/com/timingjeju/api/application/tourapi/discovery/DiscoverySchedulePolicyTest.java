package com.timingjeju.api.application.tourapi.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DiscoverySchedulePolicyTest {

  @Test
  void scheduler는_기본비활성이고_실행당_페이지와_일일호출을_모두_제한한다() {
    DiscoverySchedulePolicy policy = DiscoverySchedulePolicy.safeDefault();

    assertThat(policy.enabled()).isFalse();
    assertThat(policy.maxPagesPerRun()).isEqualTo(10);
    assertThat(policy.maxProviderCallsPerDay()).isEqualTo(100);
    assertThat(policy.minimumInterval()).isEqualTo(Duration.ofHours(1));
    assertThatThrownBy(() -> policy.requireAllowed(11, 20))
        .isInstanceOf(DiscoveryImportException.class);
    assertThatThrownBy(() -> policy.requireAllowed(5, 101))
        .isInstanceOf(DiscoveryImportException.class);
  }
}
