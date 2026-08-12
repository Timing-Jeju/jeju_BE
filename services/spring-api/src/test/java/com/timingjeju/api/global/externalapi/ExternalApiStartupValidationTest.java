package com.timingjeju.api.global.externalapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class ExternalApiStartupValidationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(ExternalApiConfiguration.class);

  @Test
  void 모든_provider가_비활성이면_key없이_시작하고_client_설정_bean을_만들지_않는다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ExternalApiClientSettings.class);
        });
  }

  @Test
  void 활성_provider는_필수_key와_base_url이_없으면_한국어_설정_오류로_실패한다() {
    contextRunner
        .withPropertyValues("app.external-api.tour-api.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("TOUR_API_API_KEY는 활성화된 provider에서 필수입니다.");
            });

    contextRunner
        .withPropertyValues(
            "app.external-api.tour-api.enabled=true",
            "app.external-api.tour-api.api-key=" + safeTestKey())
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("TOUR_API_BASE_URL는 활성화된 provider에서 필수입니다.");
            });
  }

  @Test
  void key의_공백과_placeholder는_실제_값으로_간주하지_않는다() {
    for (String value : new String[] {" ", "changeme", "replace-me", "your-api-key", "<api-key>"}) {
      contextRunner
          .withPropertyValues(
              "app.external-api.tago.enabled=true",
              "app.external-api.tago.api-key=" + value,
              "app.external-api.tago.base-url=https://apis.data.go.kr/1613000")
          .run(
              context -> {
                assertThat(context).as(value).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("TAGO_API_KEY는 실제 발급값으로 설정해야 합니다.");
              });
    }
  }

  @Test
  void 공공데이터_provider는_percent_encoded_service_key를_입력으로_받지_않는다() {
    for (String[] provider :
        new String[][] {
          {"tour-api", "TOUR_API", "https://apis.data.go.kr/B551011/KorService2"},
          {"tago", "TAGO", "https://apis.data.go.kr/1613000"},
          {"kma", "KMA", "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0"}
        }) {
      contextRunner
          .withPropertyValues(
              "app.external-api." + provider[0] + ".enabled=true",
              "app.external-api." + provider[0] + ".api-key=" + encodedServiceKey(),
              "app.external-api." + provider[0] + ".base-url=" + provider[2])
          .run(
              context -> {
                assertThat(context).as(provider[0]).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        provider[1]
                            + "_API_KEY는 decoded 원문 key여야 하며 percent-encoded 값을 허용하지 않습니다.");
              });
    }
  }

  @Test
  void TMAP_header_key는_query_percent_encoding_정책을_적용하지_않는다() {
    contextRunner
        .withPropertyValues(
            "app.external-api.tmap.enabled=true",
            "app.external-api.tmap.api-key=header%2Bvalue",
            "app.external-api.tmap.base-url=https://apis.openapi.sk.com")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void 잘못된_feature_flag는_묵시적으로_활성화하거나_비활성화하지_않는다() {
    contextRunner
        .withPropertyValues("app.external-api.kma.enabled=sometimes")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void 설정_실패_예외에는_입력한_key를_포함하지_않는다() {
    String key = safeTestKey();
    contextRunner
        .withPropertyValues(
            "app.external-api.kma.enabled=true",
            "app.external-api.kma.api-key=" + key,
            "app.external-api.kma.base-url=https://evil.example/weather")
        .run(
            context -> {
              Throwable failure = context.getStartupFailure();
              while (failure != null) {
                assertThat(failure.getMessage()).doesNotContain(key);
                if (failure.getCause() == null) {
                  assertThat(failure.getMessage()).contains("KMA_BASE_URL");
                }
                failure = failure.getCause();
              }
            });
  }

  @Test
  void 운영은_HTTP를_거부하고_정확한_local은_공식_host의_HTTP를_허용한다() {
    contextRunner
        .withPropertyValues(enabledTourApi("http://apis.data.go.kr/B551011/KorService2"))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("TOUR_API_BASE_URL는 기본/운영 환경에서 HTTPS URL이어야 합니다.");
            });

    contextRunner
        .withPropertyValues(enabledTourApi("http://apis.data.go.kr/B551011/KorService2"))
        .withPropertyValues("spring.profiles.active=local")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("tourApiClientSettings");
            });

    contextRunner
        .withPropertyValues(enabledTourApi("http://apis.data.go.kr/B551011/KorService2"))
        .withPropertyValues("spring.profiles.active=local,staging")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void provider별_허용_host와_base_path가_아니면_시작에_실패한다() {
    contextRunner
        .withPropertyValues(enabledTourApi("https://evil.example/B551011/KorService2"))
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues(enabledTourApi("https://apis.data.go.kr/1613000"))
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues(
            "app.external-api.tmap.enabled=true",
            "app.external-api.tmap.api-key=" + safeTestKey(),
            "app.external-api.tmap.base-url=https://apis.openapi.sk.com/other")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void timeout은_최소값과_최대값_안에서만_허용한다() {
    for (String timeout : new String[] {"99ms", "10001ms"}) {
      contextRunner
          .withPropertyValues(enabledTourApi("https://apis.data.go.kr/B551011/KorService2"))
          .withPropertyValues("app.external-api.tour-api.connect-timeout=" + timeout)
          .run(context -> assertThat(context).as(timeout).hasFailed());
    }
    for (String timeout : new String[] {"99ms", "30001ms"}) {
      contextRunner
          .withPropertyValues(enabledTourApi("https://apis.data.go.kr/B551011/KorService2"))
          .withPropertyValues("app.external-api.tour-api.read-timeout=" + timeout)
          .run(context -> assertThat(context).as(timeout).hasFailed());
    }

    contextRunner
        .withPropertyValues(enabledTourApi("https://apis.data.go.kr/B551011/KorService2"))
        .withPropertyValues(
            "app.external-api.tour-api.connect-timeout=100ms",
            "app.external-api.tour-api.read-timeout=30s")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void 조건부_client_설정은_typed_값을_제공하되_toString에서_key를_가린다() {
    contextRunner
        .withPropertyValues(enabledTourApi("https://apis.data.go.kr/B551011/KorService2"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ExternalApiClientSettings settings =
                  context.getBean("tourApiClientSettings", ExternalApiClientSettings.class);
              assertThat(settings.provider()).isEqualTo(ExternalApiProvider.TOUR_API);
              assertThat(settings.credential().placement())
                  .isEqualTo(ExternalApiCredentialPlacement.QUERY_SERVICE_KEY);
              assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
              assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(5));
              assertThat(settings.toString()).doesNotContain(safeTestKey()).contains("[REDACTED]");
              assertThat(context.getBean(TourApiProperties.class).toString())
                  .doesNotContain(safeTestKey(), "apis.data.go.kr")
                  .contains("apiKey=[REDACTED]", "baseUrl=[CONFIGURED]");
            });
  }

  @Test
  void actuator_info에는_provider_활성_여부만_있고_key와_url은_없다() {
    contextRunner
        .withPropertyValues(enabledTourApi("https://apis.data.go.kr/B551011/KorService2"))
        .run(
            context -> {
              Info.Builder builder = new Info.Builder();
              context
                  .getBean("externalApiInfoContributor", InfoContributor.class)
                  .contribute(builder);
              Map<String, Object> details = builder.build().getDetails();
              assertThat(details.toString())
                  .contains("tourApi=true", "tago=false", "tmap=false", "kma=false")
                  .doesNotContain(safeTestKey(), "apis.data.go.kr", "baseUrl", "apiKey");
            });
  }

  @Test
  void 네_provider는_각각_독립된_typed_설정과_조건부_client_bean을_가진다() {
    contextRunner
        .withPropertyValues(enabledTourApi("https://apis.data.go.kr/B551011/KorService2"))
        .withPropertyValues(
            "app.external-api.tago.enabled=true",
            "app.external-api.tago.api-key=" + safeTestKey(),
            "app.external-api.tago.base-url=https://apis.data.go.kr/1613000",
            "app.external-api.tmap.enabled=true",
            "app.external-api.tmap.api-key=" + safeTestKey(),
            "app.external-api.tmap.base-url=https://apis.openapi.sk.com",
            "app.external-api.kma.enabled=true",
            "app.external-api.kma.api-key=" + safeTestKey(),
            "app.external-api.kma.base-url=https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TourApiProperties.class);
              assertThat(context).hasSingleBean(TagoProperties.class);
              assertThat(context).hasSingleBean(TmapProperties.class);
              assertThat(context).hasSingleBean(KmaProperties.class);
              assertThat(context.getBeansOfType(ExternalApiClientSettings.class)).hasSize(4);
            });
  }

  private static String[] enabledTourApi(String baseUrl) {
    return new String[] {
      "app.external-api.tour-api.enabled=true",
      "app.external-api.tour-api.api-key=" + safeTestKey(),
      "app.external-api.tour-api.base-url=" + baseUrl,
      "app.external-api.tour-api.connect-timeout=2s",
      "app.external-api.tour-api.read-timeout=5s"
    };
  }

  private static String safeTestKey() {
    return "test-" + "only-provider-value";
  }

  private static String encodedServiceKey() {
    return "decoded%" + "2Bkey%2Fvalue%3D";
  }
}
