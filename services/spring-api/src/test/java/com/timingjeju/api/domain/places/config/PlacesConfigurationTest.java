package com.timingjeju.api.domain.places.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.pagination.CursorContext;
import com.timingjeju.api.application.pagination.CursorFilterFingerprint;
import com.timingjeju.api.application.pagination.CursorPosition;
import com.timingjeju.api.application.pagination.CursorSort;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class PlacesConfigurationTest {

  @Test
  void 비운영은_주입key가_없어도_process_local_무작위_HMAC_key를_사용한다() {
    CursorCodec codec =
        new PlacesConfiguration()
            .placesCursorCodec("", new MockEnvironment().withProperty("x", "y"));
    CursorContext context =
        new CursorContext(
            "/api/v1/places",
            CursorSort.asc("name", "id"),
            CursorFilterFingerprint.sha256(Map.of()));
    CursorPosition position = new CursorPosition("성산", "id");

    assertThat(codec.decode(codec.encode(context, position), context)).isEqualTo(position);
  }

  @Test
  void 운영은_32자이상_key를_반드시_주입해야_한다() {
    MockEnvironment production = new MockEnvironment().withProperty("x", "y");
    production.setActiveProfiles("production");

    assertThatThrownBy(() -> new PlacesConfiguration().placesCursorCodec("", production))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new PlacesConfiguration().placesCursorCodec("too-short", production))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new PlacesConfiguration()
                .placesCursorCodec("production-cursor-key-at-least-32-characters", production))
        .isNotNull();
  }
}
