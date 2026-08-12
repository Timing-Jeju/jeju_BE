package com.timingjeju.api.global.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ExternalApiCredentialTest {

  @Test
  void 공공데이터_service_key는_decoded_원문을_UTF8로_정확히_한번_percent_encoding한다() {
    ExternalApiCredential credential =
        ExternalApiCredential.from(ExternalApiProvider.TOUR_API, "key+/=한글");

    assertThat(credential.placement()).isEqualTo(ExternalApiCredentialPlacement.QUERY_SERVICE_KEY);
    assertThat(credential.encodedQueryValue())
        .isEqualTo("key%2B%2F%3D%ED%95%9C%EA%B8%80")
        .doesNotContain("%252B", "%252F", "%253D");
    assertThat(credential.toString()).doesNotContain("key", "한글").contains("[REDACTED]");
  }

  @Test
  void 이미_percent_encoded된_공공데이터_service_key는_value_경계에서도_거부한다() {
    for (ExternalApiProvider provider :
        new ExternalApiProvider[] {
          ExternalApiProvider.TOUR_API, ExternalApiProvider.TAGO, ExternalApiProvider.KMA
        }) {
      for (String input : new String[] {"key%2Bvalue%2Fpart%3D", "key%2bvalue%2fpart%3d"}) {
        assertThatThrownBy(() -> ExternalApiCredential.from(provider, input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                provider.environmentName("API_KEY")
                    + "는 decoded 원문 key여야 하며 percent-encoded 값을 허용하지 않습니다.");
      }
    }
  }

  @Test
  void TMAP_key는_header_원문으로만_제공하고_query_encoder를_사용할_수_없다() {
    ExternalApiCredential credential =
        ExternalApiCredential.from(ExternalApiProvider.TMAP, "header+/=%2B");

    assertThat(credential.placement()).isEqualTo(ExternalApiCredentialPlacement.HEADER_API_KEY);
    assertThat(credential.headerValue()).isEqualTo("header+/=%2B");
    assertThatThrownBy(credential::encodedQueryValue)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TMAP key는 header로만 전달해야 합니다.");
  }
}
