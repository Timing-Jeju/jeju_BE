package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTagoArrivalFlightStoreConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(StoreConfiguration.class);

  @Test
  void repository_exception_translation_proxy와_함께_context가_시작된다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(JdbcTagoArrivalFlightStore.class);
        });
  }

  @Configuration(proxyBeanMethods = false)
  static class StoreConfiguration {
    @Bean
    JdbcTemplate jdbcTemplate() {
      return mock(JdbcTemplate.class);
    }

    @Bean
    JdbcTagoArrivalFlightStore jdbcTagoArrivalFlightStore(JdbcTemplate jdbcTemplate) {
      return new JdbcTagoArrivalFlightStore(jdbcTemplate);
    }

    @Bean
    static PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslation() {
      var postProcessor = new PersistenceExceptionTranslationPostProcessor();
      postProcessor.setProxyTargetClass(true);
      return postProcessor;
    }
  }
}
