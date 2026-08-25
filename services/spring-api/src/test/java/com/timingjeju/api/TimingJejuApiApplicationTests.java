package com.timingjeju.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.global.retention.SavedPlaceRetentionProperties;
import com.timingjeju.api.global.retention.SavedPlaceRetentionScheduler;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("integration")
@SpringBootTest
class TimingJejuApiApplicationTests {

  @Autowired private SavedPlaceRetentionScheduler savedPlaceRetentionScheduler;
  @Autowired private SavedPlaceRetentionProperties savedPlaceRetentionProperties;

  @Test
  void 애플리케이션_컨텍스트는_retention을_활성화하되_scheduler경합을_지연한다() {
    assertThat(savedPlaceRetentionScheduler).isNotNull();
    assertThat(savedPlaceRetentionProperties.enabled()).isTrue();
    assertThat(savedPlaceRetentionProperties.initialDelay()).isEqualTo(Duration.ofHours(24));
  }
}
