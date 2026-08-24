package com.timingjeju.api.global.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegalDocumentsMigrationUpgradeIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final String MIGRATION_NAME = "20260901000000_legal_documents_consents.sql";

  @Autowired private DataSource dataSource;

  @Test
  void same_natural_key_different_uuid_upgrade는_23505로_fail_closed한다() throws Exception {
    assertUpgradeConflict(
        """
        insert into public.legal_documents (
          id, document_type, version, title, content_url, required, effective_at
        ) values (
          '19200000-0000-0000-0000-000000000001', 'terms', '1.0.0',
          '서비스 이용약관', 'https://timing-jeju.example/legal/terms/1.0.0', true,
          '2026-08-01T00:00:00+09:00'
        )
        """,
        "legal_document_seed_natural_key_conflict");
  }

  @Test
  void same_canonical_id_wrong_natural_upgrade는_23505로_fail_closed한다() throws Exception {
    assertUpgradeConflict(
        """
        insert into public.legal_documents (
          id, document_type, version, title, content_url, required, effective_at
        ) values (
          '09200000-0000-0000-0000-000000000001', 'privacy', '9.9.9',
          '충돌 문서', 'https://timing-jeju.example/legal/conflict/9.9.9', false,
          '2026-07-01T00:00:00+09:00'
        )
        """,
        "legal_document_seed_id_conflict");
  }

  private void assertUpgradeConflict(String legacyInsert, String expectedMessage) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        revertLegalDocumentsToPreIssue19(connection);
        connection.createStatement().execute(legacyInsert);

        Throwable failure = catchThrowable(() -> executeMigration(connection));

        SQLException sqlFailure = sqlFailure(failure);
        assertThat(sqlFailure.getSQLState()).isEqualTo("23505");
        assertThat(sqlFailure.getMessage()).contains(expectedMessage);
      } finally {
        connection.rollback();
      }
    }
  }

  private static void revertLegalDocumentsToPreIssue19(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("delete from public.user_consents");
      statement.execute("delete from public.legal_documents");
      statement.execute(
          "alter table public.legal_documents drop constraint legal_documents_locale_check");
      statement.execute(
          "alter table public.legal_documents drop constraint legal_documents_type_locale_version_key");
      statement.execute("alter table public.legal_documents drop column locale");
      statement.execute(
          "alter table public.legal_documents add constraint legal_documents_document_type_version_key unique (document_type, version)");
    }
  }

  private static Path locateMigration() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path migration = current.resolve("supabase/migrations").resolve(MIGRATION_NAME);
      if (Files.isRegularFile(migration)) {
        return migration;
      }
      current = current.getParent();
    }
    throw new AssertionError("Issue #19 migration을 찾을 수 없습니다.");
  }

  private static void executeMigration(Connection connection) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.execute(Files.readString(locateMigration()));
    }
  }

  private static SQLException sqlFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        return sqlException;
      }
      current = current.getCause();
    }
    throw new AssertionError("SQLState를 가진 migration failure가 아닙니다.", failure);
  }
}
