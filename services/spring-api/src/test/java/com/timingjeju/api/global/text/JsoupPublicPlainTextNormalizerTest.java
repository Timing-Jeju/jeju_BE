package com.timingjeju.api.global.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JsoupPublicPlainTextNormalizerTest {

  private final JsoupPublicPlainTextNormalizer normalizer = new JsoupPublicPlainTextNormalizer();

  @Test
  void script_style_event_entity_control을_제거하고_공백을_접는다() {
    String raw =
        " <script>alert('secret')</script><style>.x{display:none}</style>"
            + "<b onclick='steal()'>운영&nbsp; 안내</b>\u0000\u0007  오전\n  9시 ";

    assertThat(normalizer.normalize(raw)).isEqualTo("운영 안내 오전 9시");
  }

  @Test
  void Unicode_code_point_1000개로_자르고_blank는_null이다() {
    String normalized = normalizer.normalize("🍊".repeat(1001));

    assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(1000);
    assertThat(normalizer.normalize(" <script>only()</script> \u0000 ")).isNull();
    assertThat(normalizer.normalize(null)).isNull();
  }
}
