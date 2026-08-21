package com.timingjeju.api.domain.places.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@Tag("unit")
class PlaceNearbyStopsPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void 거리상한은_설정이_없으면_500m이고_1과_500을_허용한다() {
    contextRunner.run(
        context ->
            assertThat(context.getBean(PlaceNearbyStopsProperties.class).maxDistanceMeters())
                .isEqualTo(500));
    contextRunner
        .withPropertyValues("app.places.nearby-stops.max-distance-meters=1")
        .run(
            context ->
                assertThat(context.getBean(PlaceNearbyStopsProperties.class).maxDistanceMeters())
                    .isEqualTo(1));
    contextRunner
        .withPropertyValues("app.places.nearby-stops.max-distance-meters=500")
        .run(
            context ->
                assertThat(context.getBean(PlaceNearbyStopsProperties.class).maxDistanceMeters())
                    .isEqualTo(500));
  }

  @Test
  void 거리상한_0과_501은_startup에서_거부한다() {
    contextRunner
        .withPropertyValues("app.places.nearby-stops.max-distance-meters=0")
        .run(context -> assertThat(context).hasFailed());
    contextRunner
        .withPropertyValues("app.places.nearby-stops.max-distance-meters=501")
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PlaceNearbyStopsProperties.class)
  static class PropertiesConfiguration {}
}
