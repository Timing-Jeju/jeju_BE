package com.timingjeju.api.application.tourapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.global.tourapi.Sha256CanonicalTourApiRequestFingerprinter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CanonicalTourApiRequestFingerprinterTest {

  private final TourApiRequestFingerprinter fingerprinter =
      new Sha256CanonicalTourApiRequestFingerprinter();

  @Test
  void 같은_parameter는_입력_순서와_secret이_달라도_같은_fingerprint를_만든다() {
    Map<String, String> first = new LinkedHashMap<>();
    first.put("contentTypeId", "12");
    first.put("pageNo", "1");
    first.put("serviceKey", "first-secret");
    Map<String, String> second = new LinkedHashMap<>();
    second.put("ServiceKey", "second-secret");
    second.put("pageNo", "1");
    second.put("contentTypeId", "12");

    assertThat(fingerprinter.fingerprint(first))
        .isEqualTo(fingerprinter.fingerprint(second))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void 빈_parameter도_결정적인_SHA256을_만든다() {
    assertThat(fingerprinter.fingerprint(Map.of()))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void raw_query는_제외하고_정밀_위치는_원문_노출없이_fingerprint를_구분한다() {
    String first =
        fingerprinter.fingerprint(
            Map.of(
                "requestUrl", "https://example.test?serviceKey=secret",
                "mapX", "126.5311884",
                "mapY", "33.4996213"));
    String second =
        fingerprinter.fingerprint(
            Map.of(
                "requestUrl", "https://other.test?serviceKey=other",
                "mapX", "126.5311885",
                "mapY", "33.4996213"));

    assertThat(first).isNotEqualTo(second);
    assertThat(first).doesNotContain("126.5311884", "serviceKey", "secret", "https");
  }
}
