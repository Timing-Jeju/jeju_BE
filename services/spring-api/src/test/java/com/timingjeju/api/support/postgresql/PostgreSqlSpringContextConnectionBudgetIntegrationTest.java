package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PostgreSqlSpringContextConnectionBudgetIntegrationTest {
  private static final String IMAGE = "postgis/postgis:16-3.4";
  private static final int CONTEXT_COUNT = 4;
  private static final int CONNECTIONS_PER_CONTEXT =
      PostgreSqlTestConnectionBudget.HIKARI_PER_CONTEXT;
  private static final int PEAK_BUDGET = CONTEXT_COUNT * CONNECTIONS_PER_CONTEXT;
  private static final int CONTEXT_CACHE_BUDGET =
      PostgreSqlTestConnectionBudget.CONTEXT_CACHE_LIMIT;
  private static final int SESSION_CONNECTION_BUDGET =
      PostgreSqlTestConnectionBudget.CACHED_HIKARI_CONNECTIONS;

  @Test
  void 여러_Spring_context의_Hikari_연결은_예산안에_머물고_close후_새_context가_성공한다() throws Exception {
    PostgreSQLContainer precedingContainer = PostgreSqlTestContainerFactory.create();
    precedingContainer.start();
    PostgreSQLContainer otherPrecedingContainer = PostgreSqlTestContainerFactory.create();
    otherPrecedingContainer.start();
    List<PostgreSQLContainer> containers = new ArrayList<>();
    List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    List<Connection> heldConnections = new ArrayList<>();
    List<String> ownedDatabases = new ArrayList<>();
    List<Integer> maximumPoolSizes = new ArrayList<>();
    List<Integer> minimumIdleSizes = new ArrayList<>();
    int peakConnections;
    try (ConfigurableApplicationContext precedingContext =
            context(precedingContainer, "issue247-preceding-cached-context");
        Connection precedingConnection =
            precedingContext.getBean(HikariDataSource.class).getConnection();
        ConfigurableApplicationContext otherPrecedingContext =
            context(otherPrecedingContainer, "issue247-other-preceding-cached-context");
        Connection otherPrecedingConnection =
            otherPrecedingContext.getBean(HikariDataSource.class).getConnection()) {
      int unrelatedBaseline = PostgreSqlLauncherSessionPool.sessionCaseConnectionCount(IMAGE);
      assertThat(unrelatedBaseline).isGreaterThanOrEqualTo(2);
      for (int index = 0; index < CONTEXT_COUNT; index++) {
        PostgreSQLContainer container = PostgreSqlTestContainerFactory.create();
        container.start();
        containers.add(container);
        ownedDatabases.add(container.getDatabaseName());

        ConfigurableApplicationContext context = context(container, "issue247-context-" + index);
        contexts.add(context);
        HikariDataSource dataSource = context.getBean(HikariDataSource.class);
        maximumPoolSizes.add(dataSource.getMaximumPoolSize());
        minimumIdleSizes.add(dataSource.getMinimumIdle());
        for (int connection = 0; connection < CONNECTIONS_PER_CONTEXT; connection++) {
          heldConnections.add(dataSource.getConnection());
        }
      }
      int settledConnectionTarget =
          java.util.stream.IntStream.range(0, CONTEXT_COUNT)
              .map(
                  index ->
                      Math.max(
                          CONNECTIONS_PER_CONTEXT,
                          minimumIdleSizes.get(index) < 0
                              ? maximumPoolSizes.get(index)
                              : minimumIdleSizes.get(index)))
              .sum();
      peakConnections = awaitConnectionCount(ownedDatabases, settledConnectionTarget);
      int sessionPeakConnections = PostgreSqlLauncherSessionPool.sessionCaseConnectionCount(IMAGE);

      closeConnections(heldConnections);
      closeContexts(contexts);
      assertThat(PostgreSqlLauncherSessionPool.caseConnectionCount(IMAGE, ownedDatabases)).isZero();
      assertThat(PostgreSqlLauncherSessionPool.sessionCaseConnectionCount(IMAGE))
          .isEqualTo(unrelatedBaseline)
          .isLessThanOrEqualTo(SESSION_CONNECTION_BUDGET);

      PostgreSQLContainer replacement = PostgreSqlTestContainerFactory.create();
      replacement.start();
      containers.add(replacement);
      try (ConfigurableApplicationContext replacementContext =
          context(replacement, "issue247-context-replacement")) {
        Integer value =
            replacementContext
                .getBean(JdbcTemplate.class)
                .queryForObject("select 1", Integer.class);
        assertThat(value).isOne();
      }
      assertThat(
              PostgreSqlLauncherSessionPool.caseConnectionCount(
                  IMAGE, List.of(replacement.getDatabaseName())))
          .isZero();
      assertThat(PostgreSqlLauncherSessionPool.sessionCaseConnectionCount(IMAGE))
          .isEqualTo(unrelatedBaseline);

      assertSoftly(
          softly -> {
            softly
                .assertThat(Integer.getInteger("spring.test.context.cache.maxSize"))
                .isEqualTo(CONTEXT_CACHE_BUDGET);
            softly.assertThat(maximumPoolSizes).containsOnly(CONNECTIONS_PER_CONTEXT);
            softly.assertThat(minimumIdleSizes).containsOnly(0);
            softly.assertThat(peakConnections).isEqualTo(PEAK_BUDGET);
            softly
                .assertThat(sessionPeakConnections)
                .isEqualTo(unrelatedBaseline + peakConnections);
            softly
                .assertThat(sessionPeakConnections)
                .isLessThanOrEqualTo(SESSION_CONNECTION_BUDGET);
            softly.assertThat(SESSION_CONNECTION_BUDGET).isEqualTo(72);
          });
    } finally {
      closeConnections(heldConnections);
      closeContexts(contexts);
      for (int index = containers.size() - 1; index >= 0; index--) {
        containers.get(index).stop();
      }
      otherPrecedingContainer.stop();
      precedingContainer.stop();
    }
  }

  private static ConfigurableApplicationContext context(
      PostgreSQLContainer container, String poolName) {
    return new SpringApplicationBuilder(DataSourceContextConfiguration.class)
        .web(WebApplicationType.NONE)
        .profiles("postgresql-integration")
        .run(
            "--spring.datasource.url=" + container.getJdbcUrl(),
            "--spring.datasource.username=" + container.getUsername(),
            "--spring.datasource.password=" + container.getPassword(),
            "--spring.datasource.driver-class-name=org.postgresql.Driver",
            "--spring.datasource.hikari.pool-name=" + poolName,
            "--spring.main.banner-mode=off");
  }

  private static int awaitConnectionCount(List<String> databases, int target)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    int observed;
    do {
      observed = PostgreSqlLauncherSessionPool.caseConnectionCount(IMAGE, databases);
      if (observed >= target) return observed;
      Thread.sleep(50);
    } while (System.nanoTime() < deadline);
    return observed;
  }

  private static void closeConnections(List<Connection> connections) {
    for (int index = connections.size() - 1; index >= 0; index--) {
      try {
        connections.get(index).close();
      } catch (Exception ignored) {
        // Best-effort cleanup continues so every context can release its pool.
      }
    }
    connections.clear();
  }

  private static void closeContexts(List<ConfigurableApplicationContext> contexts) {
    for (int index = contexts.size() - 1; index >= 0; index--) {
      contexts.get(index).close();
    }
    contexts.clear();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
  static class DataSourceContextConfiguration {}
}
